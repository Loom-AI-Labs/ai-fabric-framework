package ai.fabric.relationship.it.realapi;

import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import ai.fabric.relationship.dto.FilterCondition;
import ai.fabric.relationship.dto.FilterOperator;
import ai.fabric.relationship.dto.RelationshipQueryPlan;
import ai.fabric.relationship.it.RelationshipQueryIntegrationTestApplication;
import ai.fabric.relationship.it.api.RelationshipQueryRequest;
import ai.fabric.relationship.it.config.BackendEnvTestConfiguration;
import ai.fabric.relationship.it.entity.AccountEntity;
import ai.fabric.relationship.it.entity.BrandEntity;
import ai.fabric.relationship.it.entity.ProductEntity;
import ai.fabric.relationship.it.entity.TransactionEntity;
import ai.fabric.relationship.it.repository.AccountRepository;
import ai.fabric.relationship.it.repository.BrandRepository;
import ai.fabric.relationship.it.repository.ProductRepository;
import ai.fabric.relationship.it.repository.TransactionRepository;
import ai.fabric.relationship.model.ReturnMode;
import ai.fabric.rag.VectorDatabaseService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Provider scorecard run (Real API): ALWAYS passes but emits metrics + summary artifacts.
 *
 * <p>Design goal: running the integration tests should remain stable (non-flaky), while still producing a
 * measurable "correct vs incorrect" quality signal per provider.</p>
 *
 * <p>Enable/disable and sizing are controlled via system properties:
 * <ul>
 *   <li>{@code -Dscorecard.enabled=true|false} (default: true)</li>
 *   <li>{@code -Dscorecard.runsPerScenario=3} (default: 3)</li>
 *   <li>{@code -Dscorecard.scenarioSet=relationship-query-basic-v1} (default: relationship-query-basic-v1)</li>
 * </ul>
 * </p>
 */
@Slf4j
@SpringBootTest(
    classes = RelationshipQueryIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("realapi")
@Import(BackendEnvTestConfiguration.class)
class ProviderScorecardRealApiIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Environment environment;

    @Autowired(required = false)
    private RAGOrchestrator orchestrator;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired(required = false)
    private VectorDatabaseService vectorDatabaseService;

    @Test
    void shouldGenerateProviderScorecardMetricsWithoutFailingBuild() throws Exception {
        if (!isEnabled()) {
            log.info("[Scorecard] Disabled (scorecard.enabled=false).");
            return;
        }

        String scenarioSet = System.getProperty("scorecard.scenarioSet", "relationship-query-basic-v1");
        int runsPerScenario = Integer.getInteger("scorecard.runsPerScenario", 3);
        if (runsPerScenario <= 0) {
            log.info("[Scorecard] No runs requested (scorecard.runsPerScenario <= 0).");
            return;
        }

        ScenarioSet set = loadScenarioSet(scenarioSet);

        String provider = detectProviderLabel();
        String runId = "%s-%s".formatted(scenarioSet, Instant.now().toString().replace(":", ""));
        Path outDir = Paths.get("target", "provider-scorecards", runId);
        Files.createDirectories(outDir);

        // Seed deterministic fixtures for scorecard scenarios (do NOT rely on test ordering).
        seedFixtures();

        long startedAtMs = System.currentTimeMillis();
        List<AttemptEvent> events = new ArrayList<>();

        log.info("[Scorecard] Starting runId={} provider={} scenarios={} runsPerScenario={}",
            runId, provider, set.getScenarios().size(), runsPerScenario);

        for (Scenario scenario : set.getScenarios()) {
            for (int i = 0; i < runsPerScenario; i++) {
                events.add(runAttempt(provider, scenario, i));
            }
        }

        long durationMs = Math.max(0, System.currentTimeMillis() - startedAtMs);
        ScorecardSummary summary = ScorecardSummary.from(events, provider, runId, durationMs, set.getScenarioSetId());

        writeJson(outDir.resolve("summary.json"), summary);
        writeJson(outDir.resolve("events.json"), events);

        logSummary(summary);
    }

    private AttemptEvent runAttempt(String provider, Scenario scenario, int attemptIndex) {
        long start = System.nanoTime();
        AttemptEvent ev = new AttemptEvent();
        ev.provider = provider;
        ev.scenarioId = scenario.id;
        ev.category = scenario.category;
        ev.target = scenario.target;
        ev.attempt = attemptIndex;
        ev.userQuery = scenario.userQuery;

        try {
            if (scenario.target == Target.RELATIONSHIP_QUERY_API) {
                AttemptEvent result = runRelationshipQueryApiAttempt(ev, scenario);
                result.latencyMs = elapsedMs(start);
                return result;
            }
            if (scenario.target == Target.ORCHESTRATOR) {
                AttemptEvent result = runOrchestratorAttempt(ev, scenario);
                result.latencyMs = elapsedMs(start);
                return result;
            }
            ev.correct = false;
            ev.failureCategories = List.of(FailureCategory.HARNESS_ERROR.name());
            ev.notes = "Unknown scenario target: " + scenario.target;
            ev.latencyMs = elapsedMs(start);
            return ev;
        } catch (Exception ex) {
            ev.correct = false;
            ev.failureCategories = List.of(FailureCategory.EXCEPTION.name());
            ev.notes = ex.getClass().getSimpleName() + ": " + safeMessage(ex);
            ev.latencyMs = elapsedMs(start);
            return ev;
        }
    }

    private AttemptEvent runRelationshipQueryApiAttempt(AttemptEvent ev, Scenario scenario) {
        RelationshipQueryRequest request = new RelationshipQueryRequest();
        request.setQuery(scenario.userQuery);
        request.setEntityTypes(scenario.entityTypes != null ? scenario.entityTypes : List.of());
        request.setLimit(20);
        request.setReturnMode(ReturnMode.FULL);

        ResponseEntity<RAGResponse> response = restTemplate.postForEntity(
            "/api/relationship-query/execute",
            request,
            RAGResponse.class
        );

        ev.httpStatus = response.getStatusCode().value();
        RAGResponse rag = response.getBody();
        if (rag == null) {
            ev.correct = false;
            ev.failureCategories = List.of(FailureCategory.MISSING_RESPONSE_BODY.name());
            ev.errorOutcome = true;
            return ev;
        }

        ev.success = rag.getSuccess();
        ev.errorMessage = rag.getErrorMessage();
        ev.totalResults = rag.getTotalResults();

        RelationshipQueryPlan plan = extractPlan(rag);
        ev.planPrimaryEntityType = plan != null ? plan.getPrimaryEntityType() : null;

        JudgeResult judge = judgePlanAndCompound(plan, scenario.expected);
        ev.correct = judge.correct;
        ev.failureCategories = judge.failureCategories;

        // Evidence metric: count unexpected empty results separately from plan correctness.
        Integer minResults = scenario.expected != null ? scenario.expected.minResults : null;
        if (minResults != null && minResults > 0) {
            int total = ev.totalResults != null ? ev.totalResults : 0;
            ev.unexpectedZeroResults = (total == 0);
        }

        // Evidence metric: count error outcomes (should be rare after guardrails).
        ev.errorOutcome = ev.httpStatus == null || ev.httpStatus != 200
            || Boolean.FALSE.equals(ev.success)
            || (ev.errorMessage != null && !ev.errorMessage.isBlank());

        return ev;
    }

    private AttemptEvent runOrchestratorAttempt(AttemptEvent ev, Scenario scenario) {
        if (orchestrator == null) {
            ev.correct = false;
            ev.failureCategories = List.of(FailureCategory.MISSING_ORCHESTRATOR.name());
            ev.notes = "RAGOrchestrator bean not present in this test module";
            return ev;
        }

        OrchestrationContext context = OrchestrationContext.builder()
            .userId("provider-scorecard-user")
            .sessionId("provider-scorecard-session")
            .metadata(Map.of("channel", "scorecard"))
            .build();

        OrchestrationResult result = orchestrator.orchestrate(scenario.userQuery, context);
        ev.orchestrationType = result != null ? String.valueOf(result.getType()) : null;

        if (result == null) {
            ev.correct = false;
            ev.failureCategories = List.of(FailureCategory.MISSING_ORCHESTRATION_RESULT.name());
            return ev;
        }

        if (result.getType() != OrchestrationResultType.ACTION_EXECUTED
            && result.getType() != OrchestrationResultType.COMPOUND_HANDLED) {
            ev.correct = false;
            ev.failureCategories = List.of(FailureCategory.NOT_ACTIONABLE.name());
            return ev;
        }

        Map<String, Object> payload = extractRelationshipQueryActionPayload(result);
        RelationshipQueryPlan plan = extractPlanFromActionPayload(payload);

        ev.planPrimaryEntityType = plan != null ? plan.getPrimaryEntityType() : null;
        if (plan != null) {
            ev.planOriginalQuery = plan.getOriginalQuery();
        }

        JudgeResult judge = judgePlanAndCompound(plan, scenario.expected);
        ev.correct = judge.correct;
        ev.failureCategories = judge.failureCategories;
        return ev;
    }

    private JudgeResult judgePlanAndCompound(RelationshipQueryPlan plan, Expected expected) {
        if (expected == null) {
            return JudgeResult.ok();
        }
        List<String> failures = new ArrayList<>();

        if (plan == null) {
            failures.add(FailureCategory.MISSING_PLAN.name());
            return new JudgeResult(false, failures);
        }

        if (Boolean.TRUE.equals(expected.mustHaveNoFilters)) {
            boolean hasDirect = plan.getDirectFilters() != null
                && plan.getDirectFilters().values().stream().anyMatch(list -> list != null && !list.isEmpty());
            boolean hasPaths = plan.getRelationshipPaths() != null && !plan.getRelationshipPaths().isEmpty();
            boolean hasRelFilters = plan.getRelationshipFilters() != null
                && plan.getRelationshipFilters().values().stream().anyMatch(list -> list != null && !list.isEmpty());
            if (hasDirect || hasPaths || hasRelFilters) {
                failures.add(FailureCategory.OVER_CONSTRAINT_FILTERS_PRESENT.name());
            }
        }

        List<String> planLiterals = collectPlanLiterals(plan);
        if (expected.mustIncludeLiterals != null) {
            for (String lit : expected.mustIncludeLiterals) {
                String needle = normalizeLiteral(lit);
                if (needle != null && planLiterals.stream().noneMatch(v -> v.contains(needle))) {
                    failures.add(FailureCategory.UNDER_CONSTRAINT_LITERAL.name());
                    break;
                }
            }
        }
        if (expected.mustNotIncludeLiterals != null) {
            for (String lit : expected.mustNotIncludeLiterals) {
                String needle = normalizeLiteral(lit);
                if (needle != null && planLiterals.stream().anyMatch(v -> v.contains(needle))) {
                    failures.add(FailureCategory.OVER_CONSTRAINT_LITERAL.name());
                    break;
                }
            }
        }

        if (expected.mustHaveNumericConstraints != null) {
            for (NumericConstraint c : expected.mustHaveNumericConstraints) {
                if (!matchesNumericConstraint(plan, c)) {
                    failures.add(FailureCategory.UNDER_CONSTRAINT_RANGE.name());
                    break;
                }
            }
        }

        if (Boolean.TRUE.equals(expected.mustHaveCrossEntityComparison)) {
            if (!hasCrossEntityComparison(plan)) {
                failures.add(FailureCategory.UNDER_CONSTRAINT_RANGE.name());
            }
        }

        if (expected.relationalQueryMustContain != null) {
            String original = plan.getOriginalQuery() != null ? plan.getOriginalQuery() : "";
            String normalized = original.toLowerCase(Locale.ROOT);
            for (String must : expected.relationalQueryMustContain) {
                if (must != null && !normalized.contains(must.toLowerCase(Locale.ROOT))) {
                    failures.add(FailureCategory.COMPOUND_QUERY_NOT_STRIPPED.name());
                    break;
                }
            }
        }
        if (expected.relationalQueryMustNotContain != null) {
            String original = plan.getOriginalQuery() != null ? plan.getOriginalQuery() : "";
            String normalized = original.toLowerCase(Locale.ROOT);
            for (String forbidden : expected.relationalQueryMustNotContain) {
                if (forbidden != null && normalized.contains(forbidden.toLowerCase(Locale.ROOT))) {
                    failures.add(FailureCategory.COMPOUND_QUERY_NOT_STRIPPED.name());
                    break;
                }
            }
        }

        return failures.isEmpty() ? JudgeResult.ok() : new JudgeResult(false, failures);
    }

    private boolean hasCrossEntityComparison(RelationshipQueryPlan plan) {
        if (plan == null) {
            return false;
        }
        List<FilterCondition> all = new ArrayList<>();
        if (plan.getDirectFilters() != null) {
            plan.getDirectFilters().values().forEach(list -> {
                if (list != null) {
                    all.addAll(list);
                }
            });
        }
        if (plan.getRelationshipPaths() != null) {
            plan.getRelationshipPaths().forEach(path -> {
                if (path != null && path.getConditions() != null) {
                    all.addAll(path.getConditions());
                }
            });
        }
        for (FilterCondition cond : all) {
            if (cond == null) {
                continue;
            }
            if (cond.getValue() instanceof String s && s.contains(".")) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesNumericConstraint(RelationshipQueryPlan plan, NumericConstraint c) {
        if (plan == null || c == null || c.field == null || c.op == null) {
            return false;
        }
        String field = c.field.toLowerCase(Locale.ROOT);
        String op = c.op.toLowerCase(Locale.ROOT);

        List<FilterCondition> all = new ArrayList<>();
        if (plan.getDirectFilters() != null) {
            plan.getDirectFilters().values().forEach(list -> {
                if (list != null) {
                    all.addAll(list);
                }
            });
        }
        if (plan.getRelationshipPaths() != null) {
            plan.getRelationshipPaths().forEach(path -> {
                if (path != null && path.getConditions() != null) {
                    all.addAll(path.getConditions());
                }
            });
        }

        for (FilterCondition cond : all) {
            if (cond == null || cond.getOperator() == null || cond.getField() == null) {
                continue;
            }
            String condField = cond.getField().toLowerCase(Locale.ROOT);
            boolean fieldMatches = condField.endsWith(field) || condField.contains("." + field);
            if (!fieldMatches) {
                continue;
            }

            if ("<".equals(op)) {
                if (cond.getOperator() == FilterOperator.LESS_THAN || cond.getOperator() == FilterOperator.LESS_THAN_OR_EQUAL) {
                    return Objects.equals(asInt(cond.getValue()), c.value);
                }
            } else if (">".equals(op)) {
                if (cond.getOperator() == FilterOperator.GREATER_THAN || cond.getOperator() == FilterOperator.GREATER_THAN_OR_EQUAL) {
                    return Objects.equals(asInt(cond.getValue()), c.value);
                }
            } else if ("between".equals(op)) {
                if (cond.getOperator() == FilterOperator.BETWEEN) {
                    Integer min = asInt(cond.getValue());
                    Integer max = asInt(cond.getSecondaryValue());
                    return Objects.equals(min, c.min) && Objects.equals(max, c.max);
                }
            }
        }
        return false;
    }

    private Integer asInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> collectPlanLiterals(RelationshipQueryPlan plan) {
        List<String> literals = new ArrayList<>();
        if (plan == null) {
            return literals;
        }

        List<FilterCondition> all = new ArrayList<>();
        if (plan.getDirectFilters() != null) {
            plan.getDirectFilters().values().forEach(list -> {
                if (list != null) {
                    all.addAll(list);
                }
            });
        }
        if (plan.getRelationshipPaths() != null) {
            plan.getRelationshipPaths().forEach(path -> {
                if (path != null && path.getConditions() != null) {
                    all.addAll(path.getConditions());
                }
            });
        }

        for (FilterCondition cond : all) {
            if (cond == null) {
                continue;
            }
            addNormalizedValueLiterals(cond.getValue(), literals);
            addNormalizedValueLiterals(cond.getSecondaryValue(), literals);
        }

        return literals;
    }

    private void addNormalizedValueLiterals(Object raw, List<String> out) {
        if (raw == null) {
            return;
        }
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                addNormalizedValueLiterals(item, out);
            }
            return;
        }
        if (raw instanceof String s) {
            String normalized = normalizeLiteral(s);
            if (normalized != null) {
                out.add(normalized);
            }
        }
    }

    private String normalizeLiteral(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) {
            return null;
        }
        v = v.replace("\"", "").replace("'", "");
        v = v.replace("%", "").replace("*", "");
        v = v.replaceAll("\\s+", " ").trim();
        return v.isBlank() ? null : v;
    }

    private RelationshipQueryPlan extractPlan(RAGResponse rag) {
        if (rag == null || rag.getMetadata() == null) {
            return null;
        }
        Object planObj = rag.getMetadata().get("plan");
        if (planObj == null) {
            return null;
        }
        if (planObj instanceof RelationshipQueryPlan plan) {
            return plan;
        }
        if (planObj instanceof Map<?, ?>) {
            return objectMapper.convertValue(planObj, RelationshipQueryPlan.class);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractRelationshipQueryActionPayload(OrchestrationResult result) {
        if (result.getType() == OrchestrationResultType.ACTION_EXECUTED) {
            ActionResult actionResult = (ActionResult) result.getData().get("actionResult");
            return actionResult.getData().toMap();
        }

        if (result.getType() == OrchestrationResultType.COMPOUND_HANDLED) {
            OrchestrationResult child = result.getChildren().stream()
                .filter(r -> r != null && r.getType() == OrchestrationResultType.ACTION_EXECUTED)
                .filter(r -> {
                    Object action = r.getData() != null ? r.getData().get("action") : null;
                    return action != null && "relationship_query".equalsIgnoreCase(action.toString());
                })
                .findFirst()
                .orElse(null);
            if (child == null) {
                return Map.of();
            }
            ActionResult actionResult = (ActionResult) child.getData().get("actionResult");
            return actionResult.getData().toMap();
        }

        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private RelationshipQueryPlan extractPlanFromActionPayload(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Object metadataObj = payload.get("metadata");
        if (!(metadataObj instanceof Map<?, ?> meta)) {
            return null;
        }
        Object planObj = ((Map<String, Object>) meta).get("plan");
        if (planObj instanceof RelationshipQueryPlan plan) {
            return plan;
        }
        if (planObj instanceof Map<?, ?>) {
            return objectMapper.convertValue(planObj, RelationshipQueryPlan.class);
        }
        return null;
    }

    private ScenarioSet loadScenarioSet(String scenarioSet) throws Exception {
        String path = "provider-scorecards/%s.json".formatted(scenarioSet);
        try (InputStream in = ProviderScorecardRealApiIntegrationTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Scenario set not found on classpath: " + path);
            }
            return objectMapper.readValue(in, ScenarioSet.class);
        }
    }

    private void writeJson(Path path, Object payload) throws Exception {
        Files.createDirectories(path.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), payload);
    }

    private void logSummary(ScorecardSummary summary) {
        if (summary == null) {
            return;
        }
        log.info("[Scorecard] provider={} runId={} scenarioSet={} attempts={} correct={} correctRate={} durationMs={}",
            summary.provider,
            summary.runId,
            summary.scenarioSetId,
            summary.totalAttempts,
            summary.correctAttempts,
            summary.correctRate,
            summary.durationMs
        );
        log.info("[Scorecard] evidence: unexpectedZeroResults={} errorAttempts={} latencyP50Ms={} latencyP95Ms={}",
            summary.unexpectedZeroResultsAttempts,
            summary.errorAttempts,
            summary.latencyP50Ms,
            summary.latencyP95Ms
        );
        if (summary.failureCounts != null && !summary.failureCounts.isEmpty()) {
            String failures = summary.failureCounts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
            log.info("[Scorecard] failureCounts: {}", failures);
        }
    }

    private boolean isEnabled() {
        String prop = System.getProperty("scorecard.enabled");
        if (prop == null) {
            return true;
        }
        return Boolean.parseBoolean(prop);
    }

    private long elapsedMs(long startNano) {
        return Math.max(0, (System.nanoTime() - startNano) / 1_000_000);
    }

    private String detectProviderLabel() {
        String orchestration = environment.getProperty("ai.providers.orchestration.llm-provider");
        String generation = environment.getProperty("ai.providers.generation.llm-provider");
        String global = environment.getProperty("ai.providers.llm-provider");
        String resolved = orchestration != null ? orchestration : (global != null ? global : "unknown");
        if (generation != null && !Objects.equals(generation, resolved)) {
            return "%s (gen=%s)".formatted(resolved, generation);
        }
        return resolved;
    }

    private String safeMessage(Exception ex) {
        String msg = ex.getMessage();
        return msg != null ? msg : "";
    }

    enum Target { RELATIONSHIP_QUERY_API, ORCHESTRATOR }

    enum FailureCategory {
        MISSING_RESPONSE_BODY,
        MISSING_PLAN,
        MISSING_ORCHESTRATOR,
        MISSING_ORCHESTRATION_RESULT,
        NOT_ACTIONABLE,
        OVER_CONSTRAINT_FILTERS_PRESENT,
        OVER_CONSTRAINT_LITERAL,
        UNDER_CONSTRAINT_LITERAL,
        UNDER_CONSTRAINT_RANGE,
        COMPOUND_QUERY_NOT_STRIPPED,
        HARNESS_ERROR,
        EXCEPTION
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ScenarioSet {
        private String scenarioSetId;
        private String description;
        private List<Scenario> scenarios = List.of();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Scenario {
        private String id;
        private Target target = Target.RELATIONSHIP_QUERY_API;
        private String category;
        private String userQuery;
        private List<String> entityTypes;
        private Expected expected;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Expected {
        private Boolean mustHaveNoFilters;
        private List<String> mustIncludeLiterals;
        private List<String> mustNotIncludeLiterals;
        private List<NumericConstraint> mustHaveNumericConstraints;
        private Boolean mustHaveCrossEntityComparison;
        private Integer minResults;
        private List<String> relationalQueryMustContain;
        private List<String> relationalQueryMustNotContain;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class NumericConstraint {
        private String field;
        private String op;
        private Integer value;
        private Integer min;
        private Integer max;
    }

    @Data
    static class AttemptEvent {
        public String provider;
        public String scenarioId;
        public String category;
        public Target target;
        public int attempt;
        public String userQuery;
        public Integer httpStatus;
        public Boolean success;
        public String errorMessage;
        public Integer totalResults;
        public String orchestrationType;
        public String planPrimaryEntityType;
        public String planOriginalQuery;
        public boolean correct;
        public boolean unexpectedZeroResults;
        public boolean errorOutcome;
        public List<String> failureCategories = List.of();
        public String notes;
        public long latencyMs;
    }

    @Data
    static class JudgeResult {
        boolean correct;
        List<String> failureCategories;

        JudgeResult() {
        }

        JudgeResult(boolean correct, List<String> failureCategories) {
            this.correct = correct;
            this.failureCategories = failureCategories != null ? failureCategories : List.of();
        }

        static JudgeResult ok() {
            return new JudgeResult(true, List.of());
        }
    }

    @Data
    static class ScorecardSummary {
        public String provider;
        public String runId;
        public String scenarioSetId;
        public long durationMs;
        public int totalAttempts;
        public int correctAttempts;
        public double correctRate;
        public int unexpectedZeroResultsAttempts;
        public int errorAttempts;
        public Map<String, Integer> failureCounts;
        public long latencyP50Ms;
        public long latencyP95Ms;

        static ScorecardSummary from(List<AttemptEvent> events,
                                    String provider,
                                    String runId,
                                    long durationMs,
                                    String scenarioSetId) {
            ScorecardSummary s = new ScorecardSummary();
            s.provider = provider;
            s.runId = runId;
            s.durationMs = durationMs;
            s.scenarioSetId = scenarioSetId;
            s.totalAttempts = events != null ? events.size() : 0;
            s.correctAttempts = events != null ? (int) events.stream().filter(e -> e != null && e.correct).count() : 0;
            s.correctRate = s.totalAttempts == 0 ? 0.0 : (double) s.correctAttempts / (double) s.totalAttempts;
            s.unexpectedZeroResultsAttempts = events != null ? (int) events.stream().filter(e -> e != null && e.unexpectedZeroResults).count() : 0;
            s.errorAttempts = events != null ? (int) events.stream().filter(e -> e != null && e.errorOutcome).count() : 0;

            Map<String, Integer> failures = new LinkedHashMap<>();
            if (events != null) {
                for (AttemptEvent e : events) {
                    if (e == null || e.failureCategories == null) {
                        continue;
                    }
                    for (String cat : e.failureCategories) {
                        if (cat == null) {
                            continue;
                        }
                        failures.put(cat, failures.getOrDefault(cat, 0) + 1);
                    }
                }
            }
            s.failureCounts = failures;

            List<Long> latencies = events == null ? List.of() : events.stream()
                .filter(Objects::nonNull)
                .map(e -> e.latencyMs)
                .sorted(Comparator.naturalOrder())
                .toList();
            s.latencyP50Ms = percentile(latencies, 50);
            s.latencyP95Ms = percentile(latencies, 95);
            return s;
        }

        private static long percentile(List<Long> values, int p) {
            if (values == null || values.isEmpty()) {
                return 0;
            }
            int idx = (int) Math.ceil((p / 100.0) * values.size()) - 1;
            idx = Math.max(0, Math.min(idx, values.size() - 1));
            return values.get(idx);
        }
    }

    private void seedFixtures() {
        productRepository.deleteAllInBatch();
        brandRepository.deleteAllInBatch();
        transactionRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        if (vectorDatabaseService != null) {
            try {
                vectorDatabaseService.clearVectors();
            } catch (Exception ignored) {
            }
        }

        // ECommerce fixtures
        BrandEntity nike = new BrandEntity();
        nike.setName("Nike");
        BrandEntity adidas = new BrandEntity();
        adidas.setName("Adidas");
        nike = brandRepository.save(nike);
        adidas = brandRepository.save(adidas);

        ProductEntity nikeBlueRunner = product("Blue Runner Running Shoes", "blue", BigDecimal.valueOf(85), "ACTIVE", nike);
        ProductEntity adidasBlue = product("Adidas Flex Shoes", "blue", BigDecimal.valueOf(95), "ACTIVE", adidas);
        ProductEntity adidasRunner = product("Adidas Runner Shoes Elite", "red", BigDecimal.valueOf(110), "ACTIVE", adidas);
        productRepository.saveAll(List.of(nikeBlueRunner, adidasBlue, adidasRunner));

        // Financial fraud fixtures (mirror counterparty)
        AccountEntity origin = account("origin-account.ownerName", "high-risk region", BigDecimal.valueOf(0.83));
        AccountEntity destination = account(origin.getOwnerName(), "high-risk corridor", BigDecimal.valueOf(0.22));
        origin = accountRepository.save(origin);
        destination = accountRepository.save(destination);

        TransactionEntity suspiciousWire = new TransactionEntity();
        suspiciousWire.setTitle("Pending Wire 40k");
        suspiciousWire.setAmount(BigDecimal.valueOf(40_000));
        suspiciousWire.setCurrency("USD");
        suspiciousWire.setChannel("Wire");
        suspiciousWire.setStatus("PENDING_REVIEW");
        suspiciousWire.setOccurredAt(LocalDateTime.now().minusHours(4));
        suspiciousWire.setSourceAccount(origin);
        suspiciousWire.setDestinationAccount(destination);
        suspiciousWire = transactionRepository.save(suspiciousWire);
    }

    private ProductEntity product(String name, String color, BigDecimal price, String status, BrandEntity brand) {
        ProductEntity product = new ProductEntity();
        product.setName(name);
        product.setColor(color);
        product.setPrice(price);
        product.setStatus(status);
        product.setBrand(brand);
        brand.getProducts().add(product);
        return product;
    }

    private AccountEntity account(String owner, String region, BigDecimal risk) {
        AccountEntity account = new AccountEntity();
        account.setOwnerName(owner);
        account.setRegion(region);
        account.setRiskScore(risk);
        return account;
    }

}
