package ai.fabric.it.realapi;

import ai.fabric.embedding.EmbeddingProvider;
import ai.fabric.it.TestApplication;
import ai.fabric.provider.AIProvider;
import ai.fabric.provider.AIProviderManager;
import ai.fabric.provider.registry.ProviderDefinition;
import ai.fabric.provider.registry.ProviderRegistryService;
import ai.fabric.provider.registry.ProviderType;
import ai.fabric.intent.orchestration.OrchestrationResultDebugSnapshotStore;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Shared harness for running suites of integration tests across multiple
 * provider combinations (LLM / Embedding / optional Vector DB).
 */
abstract class AbstractProviderMatrixIntegrationTest {

    protected static final Logger log = LoggerFactory.getLogger(AbstractProviderMatrixIntegrationTest.class);

    private static final String VECTORDB_PROPERTY = "ai.vector-db.type";
    private final AtomicInteger testCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final List<CombinationScore> scores = Collections.synchronizedList(new ArrayList<>());
    private volatile Path scorecardPath;

    @TestFactory
	    Stream<DynamicTest> providerMatrix() {
	        beforeMatrixExecution();
	        List<ProviderCombination> combinations = resolveProviderMatrix();
	        Assertions.assertThat(combinations)
	            .as("Provider matrix should not be empty")
	            .isNotEmpty();

	        // Filter combinations based on provider-specific rules
	        List<ProviderCombination> filteredCombinations = filterProviderCombinations(combinations);
	        if (filteredCombinations.isEmpty()) {
	            Assumptions.assumeTrue(false,
	                "No runnable provider combinations after filtering. " +
	                    "This usually means required provider configuration is missing for the requested matrix.");
	        }

	        if (filteredCombinations.size() < combinations.size()) {
	            log.info("Filtered {} combinations down to {} based on provider-specific rules",
	                combinations.size(), filteredCombinations.size());
	        }

        log.info("═══════════════════════════════════════════════════════════════");
        log.info("{} - Starting", suiteDisplayName());
        log.info("Total combinations to test: {}", filteredCombinations.size());
        log.info("─────────────────────────────────────────────────────────────");
        filteredCombinations.forEach(combo -> log.info("  • {}", combo.displayName()));
        log.info("═══════════════════════════════════════════════════════════════");

        testCount.set(0);
        successCount.set(0);
        scores.clear();
        scorecardPath = initScorecardPath();

        return filteredCombinations.stream()
            .map(combo -> DynamicTest.dynamicTest(
                combo.displayName(),
                () -> executeCombination(filteredCombinations.size(), combo)
            ));
    }

    /**
     * Filter provider combinations based on provider-specific rules.
     * Override this method in subclasses to implement custom filtering.
     */
    protected List<ProviderCombination> filterProviderCombinations(List<ProviderCombination> combinations) {
        List<ProviderCombination> filtered = new ArrayList<>();

        for (ProviderCombination combo : combinations) {
            if (shouldIncludeCombination(combo)) {
                filtered.add(combo);
            } else {
                log.debug("Filtered out combination: {} (provider-specific rule)", combo.displayName());
            }
        }

        return filtered;
    }

    /**
     * Determine if a provider combination should be included in tests.
     * Override this method to implement custom filtering logic.
     */
    protected boolean shouldIncludeCombination(ProviderCombination combo) {
        // Default: include all combinations
        // Override in subclasses for provider-specific filtering

        // Example: Skip ONNX as LLM (it's only for embeddings)
        if ("onnx".equals(combo.llmProvider())) {
            return false;
        }

        return true;
    }

    private void executeCombination(int total, ProviderCombination combo) {
        testCount.incrementAndGet();
        log.info("[{}/{}] Running tests for: {}", testCount.get(), total, combo.displayName());

        // Validate provider combination before execution
        try {
            validateProviderCombination(combo);
        } catch (IllegalArgumentException e) {
            log.error("❌ Provider combination validation failed: {}", e.getMessage());
            throw e;
        }

        long startTime = System.currentTimeMillis();
        configureProviderProperties(combo);

        try {
            // Ensure snapshots printed on failure belong to this run, not a previous combo.
            OrchestrationResultDebugSnapshotStore.clear();

            SummaryGeneratingListener listener = new SummaryGeneratingListener();
            Launcher launcher = LauncherFactory.create();

            Class<?>[] testClasses = suiteTestClasses();
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("Executing " + testClasses.length + " test classes for: " + combo.displayName());
            System.out.println("═══════════════════════════════════════════════════════════════");
            for (int i = 0; i < testClasses.length; i++) {
                System.out.println(String.format("  [%d/%d] %s", i + 1, testClasses.length, testClasses[i].getSimpleName()));
            }
            System.out.println("═══════════════════════════════════════════════════════════════");

            log.info("Executing {} test classes for combination: {}", testClasses.length, combo.displayName());
            for (int i = 0; i < testClasses.length; i++) {
                log.info("  [{}/{}] {}", i + 1, testClasses.length, testClasses[i].getSimpleName());
            }

            LauncherDiscoveryRequestBuilder requestBuilder = LauncherDiscoveryRequestBuilder.request();
            for (Class<?> testClass : testClasses) {
                requestBuilder.selectors(selectClass(testClass));
            }

            LauncherDiscoveryRequest request = requestBuilder.build();
            launcher.execute(request, listener);

            TestExecutionSummary summary = listener.getSummary();
            long duration = System.currentTimeMillis() - startTime;

            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("Test Execution Summary:");
            System.out.println("  Tests Found: " + summary.getTestsFoundCount());
            System.out.println("  Tests Failed: " + summary.getTotalFailureCount());
            System.out.println("  Tests Skipped: " + summary.getTestsSkippedCount());
            System.out.println("  Tests Aborted: " + summary.getTestsAbortedCount());
            System.out.println("  Duration: " + duration + " ms");
            System.out.println("═══════════════════════════════════════════════════════════════");

            log.info("Test execution summary: {} tests found, {} failures, {} skipped, {} aborted",
                summary.getTestsFoundCount(), summary.getTotalFailureCount(),
                summary.getTestsSkippedCount(), summary.getTestsAbortedCount());

            String failures = summary.getFailures().stream()
                .map(failure -> {
                    String testName = failure.getTestIdentifier().getDisplayName();
                    Throwable ex = failure.getException();
                    String errorMsg = ex != null ? ex.getMessage() : "Unknown error";
                    String errorType = ex != null ? ex.getClass().getSimpleName() : "UnknownException";

                    String location = "";
                    if (ex != null) {
                        StackTraceElement[] stack = ex.getStackTrace();
                        if (stack != null) {
                            for (StackTraceElement el : stack) {
                                if (el == null) {
                                    continue;
                                }
                                String cn = el.getClassName();
                                if (cn != null && cn.startsWith("ai.fabric")) {
                                    location = " @ " + el.getFileName() + ":" + el.getLineNumber();
                                    break;
                                }
                            }
                        }
                    }
                    // Truncate very long error messages
                    if (errorMsg != null && errorMsg.length() > 200) {
                        errorMsg = errorMsg.substring(0, 197) + "...";
                    }
                    return testName + " -> " + errorType + ": " + errorMsg + location;
                })
                .collect(Collectors.joining(System.lineSeparator()));

            OrchestrationResultDebugSnapshotStore.Snapshot snapshot = OrchestrationResultDebugSnapshotStore.getLast();
            if (snapshot != null) {
                log.info("Last normalized result snapshot: {}", snapshot);
            }

	            CombinationScore score = CombinationScore.from(combo, summary, duration, failures, snapshot);
	            scores.add(score);
	            writeScorecardQuietly();

	            int configuredMinimumConsideredTests = minimumConsideredTests();
	            long maxPossibleConsideredTests = Math.max(0,
	                score.testsFound() - score.testsSkipped() - score.testsAborted());
	            int effectiveMinimumConsideredTests = configuredMinimumConsideredTests;
	            if (configuredMinimumConsideredTests > maxPossibleConsideredTests && maxPossibleConsideredTests > 0) {
	                effectiveMinimumConsideredTests = (int) Math.min(Integer.MAX_VALUE, maxPossibleConsideredTests);
	                log.warn("Configured minimumConsideredTests={} exceeds max possible considered tests for this run (testsFound={}, skipped={}, aborted={}). Clamping to {}.",
	                    configuredMinimumConsideredTests,
	                    score.testsFound(),
	                    score.testsSkipped(),
	                    score.testsAborted(),
	                    effectiveMinimumConsideredTests);
	            }

	            boolean hasFailures = summary.getTotalFailureCount() > 0;
	            boolean insufficientData = score.consideredTests() < effectiveMinimumConsideredTests;

	            if ((hasFailures || insufficientData) && !score.meetsThreshold(minimumSuccessRate(), effectiveMinimumConsideredTests)) {
	                log.error("✗ FAILED: {} ({} ms) successRate={} threshold={} consideredTests={} (succeeded={}, failed={}, skipped={}, aborted={})",
	                    combo.displayName(),
	                    duration,
	                    String.format("%.2f", score.successRate()),
	                    String.format("%.2f", minimumSuccessRate()),
                    score.consideredTests(),
                    score.testsSucceeded(),
                    score.testsFailed(),
	                    score.testsSkipped(),
	                    score.testsAborted());
	                if (insufficientData) {
	                    log.error("Insufficient data: consideredTests={} is below minimumConsideredTests={}",
	                        score.consideredTests(), effectiveMinimumConsideredTests);
	                }
	                log.error("Provider combination: LLM={}, Embedding={}, VectorDB={}",
	                    combo.llmProvider(), combo.embeddingProvider(),
	                    combo.vectorDbProvider() != null ? combo.vectorDbProvider() : "default");
	                if (StringUtils.hasText(failures)) {
                    log.error("Failures:\n{}", failures);
                }

                List<OrchestrationResultDebugSnapshotStore.Snapshot> recent = OrchestrationResultDebugSnapshotStore.getRecent();
                if (!recent.isEmpty()) {
                    int from = Math.max(0, recent.size() - 5);
                    log.error("Recent normalized result snapshots (last {}): {}", (recent.size() - from),
                        recent.subList(from, recent.size()));
                }

                String snapshotLine = snapshot != null
                    ? "Last normalized result snapshot: " + snapshot
                    : "Last normalized result snapshot: (none captured)";

                throw new AssertionError("Failures detected for " + combo.displayName() + " (" +
                    summary.getTotalFailureCount() + " failures)" +
                    System.lineSeparator() + snapshotLine +
                    System.lineSeparator() + failures);
	            }

	            if (summary.getTotalFailureCount() > 0) {
	                log.warn("⚠ SOFT FAIL (within threshold): {} successRate={} threshold={} consideredTests={} (failed={})",
	                    combo.displayName(),
	                    String.format("%.2f", score.successRate()),
	                    String.format("%.2f", minimumSuccessRate()),
	                    score.consideredTests(),
	                    score.testsFailed());
	            }

            log.info("✓ Combination completed successfully in {} ms. Tests: {}, Failures: {}, Skipped: {}",
                duration, summary.getTestsFoundCount(), summary.getTotalFailureCount(), summary.getTestsSkippedCount());
            successCount.incrementAndGet();
            log.info("✓ [{}] PASSED: {}", testCount.get(), combo.displayName());

        } finally {
            clearProviderProperties(combo);
        }
    }

    protected double minimumSuccessRate() {
        String raw = firstNonBlank(
            System.getProperty("ai.providers.matrix.minimum-success-rate"),
            System.getenv("AI_PROVIDERS_MATRIX_MINIMUM_SUCCESS_RATE")
        );
        if (!StringUtils.hasText(raw)) {
            return 1.0d;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception ignored) {
            return 1.0d;
        }
    }

    protected int minimumConsideredTests() {
        String raw = firstNonBlank(
            System.getProperty("ai.providers.matrix.minimum-considered-tests"),
            System.getenv("AI_PROVIDERS_MATRIX_MINIMUM_CONSIDERED_TESTS")
        );
        if (!StringUtils.hasText(raw)) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (Exception ignored) {
            return 1;
        }
    }

    private Path initScorecardPath() {
        String fileName = suiteDisplayName()
            .toLowerCase()
            .replace(' ', '-')
            .replace('/', '-')
            .replace('\\', '-')
            + "-provider-matrix-scorecard-" + Instant.now().toEpochMilli() + ".json";
        return Path.of("target", "provider-matrix-reports", fileName);
    }

    private void writeScorecardQuietly() {
        Path path = scorecardPath;
        if (path == null) {
            return;
        }
        try {
            writeScorecard(path);
        } catch (Exception e) {
            log.warn("Failed to write provider matrix scorecard: {}", e.getMessage());
        }
    }

    private void writeScorecard(Path path) throws IOException {
        Files.createDirectories(path.getParent());

        StringBuilder json = new StringBuilder(1024);
        json.append("{\n");
        json.append("  \"suite\": ").append(toJsonString(suiteDisplayName())).append(",\n");
        json.append("  \"minimumSuccessRate\": ").append(minimumSuccessRate()).append(",\n");
        json.append("  \"minimumConsideredTests\": ").append(minimumConsideredTests()).append(",\n");
        json.append("  \"generatedAt\": ").append(toJsonString(Instant.now().toString())).append(",\n");
        json.append("  \"summaryByLlmProvider\": {\n");

        synchronized (scores) {
            Map<String, AggregateScore> byLlm = new java.util.LinkedHashMap<>();
            for (CombinationScore score : scores) {
                AggregateScore current = byLlm.get(score.llmProvider());
                if (current == null) {
                    current = new AggregateScore(0, 0, 0, 0);
                }
                byLlm.put(score.llmProvider(), current.add(score));
            }

            int idx = 0;
            for (Map.Entry<String, AggregateScore> entry : byLlm.entrySet()) {
                json.append("    ")
                    .append(toJsonString(entry.getKey()))
                    .append(": ")
                    .append(entry.getValue().toJson());
                if (idx < byLlm.size() - 1) {
                    json.append(",");
                }
                json.append("\n");
                idx++;
            }
        }

        json.append("  },\n");
        json.append("  \"summaryByExtractionPath\": {\n");

        synchronized (scores) {
            Map<String, Long> byPath = new java.util.LinkedHashMap<>();
            for (CombinationScore score : scores) {
                String pathValue = score.extractionPath();
                if (!StringUtils.hasText(pathValue)) {
                    continue;
                }
                byPath.put(pathValue, byPath.getOrDefault(pathValue, 0L) + 1L);
            }

            int idx = 0;
            for (Map.Entry<String, Long> entry : byPath.entrySet()) {
                json.append("    ")
                    .append(toJsonString(entry.getKey()))
                    .append(": ")
                    .append(entry.getValue());
                if (idx < byPath.size() - 1) {
                    json.append(",");
                }
                json.append("\n");
                idx++;
            }
        }

        json.append("  },\n");
        json.append("  \"combinations\": [\n");

        synchronized (scores) {
            for (int i = 0; i < scores.size(); i++) {
                CombinationScore score = scores.get(i);
                json.append(score.toJson("    "));
                if (i < scores.size() - 1) {
                    json.append(",");
                }
                json.append("\n");
            }
        }

        json.append("  ]\n");
        json.append("}\n");

        Files.writeString(path, json.toString());
    }

    private static String toJsonString(String value) {
        if (value == null) {
            return "null";
        }
        String escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }

    private static String toJsonStringList(List<String> values) {
        if (values == null) {
            return "null";
        }
        if (values.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            sb.append(toJsonString(values.get(i)));
            if (i < values.size() - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private record CombinationScore(
        String combination,
        String llmProvider,
        String embeddingProvider,
        String vectorDbProvider,
        long durationMs,
        long testsFound,
        long testsSucceeded,
        long testsFailed,
        long testsSkipped,
        long testsAborted,
        double successRate,
        String failures,
        String lastSnapshot,
        String extractionPath,
        Integer llmCalls,
        List<String> issueCodes,
        List<String> normalizationRules
    ) {
        static CombinationScore from(ProviderCombination combo,
                                     TestExecutionSummary summary,
                                     long durationMs,
                                     String failures,
                                     OrchestrationResultDebugSnapshotStore.Snapshot snapshot) {
            long succeeded = safeCount(summary::getTestsSucceededCount);
            long failed = safeCount(summary::getTestsFailedCount);
            long skipped = safeCount(summary::getTestsSkippedCount);
            long aborted = safeCount(summary::getTestsAbortedCount);
            long found = safeCount(summary::getTestsFoundCount);

            long considered = Math.max(0, succeeded + failed);
            double rate = considered == 0 ? 1.0d : ((double) succeeded / (double) considered);

            String vector = combo.vectorDbProvider() != null ? combo.vectorDbProvider() : "default";
            return new CombinationScore(
                combo.displayName(),
                combo.llmProvider(),
                combo.embeddingProvider(),
                vector,
                durationMs,
                found,
                succeeded,
                failed,
                skipped,
                aborted,
                rate,
                StringUtils.hasText(failures) ? failures : null,
                snapshot != null ? snapshot.toString() : null,
                snapshot != null ? snapshot.extractionPath() : null,
                snapshot != null ? snapshot.llmCalls() : null,
                snapshot != null ? snapshot.issueCodes() : null,
                snapshot != null ? snapshot.normalizationRules() : null
            );
        }

        boolean meetsThreshold(double threshold, int minimumConsideredTests) {
            long considered = consideredTests();
            if (considered < minimumConsideredTests) {
                return false;
            }
            return successRate >= threshold;
        }

        long consideredTests() {
            return Math.max(0, testsSucceeded + testsFailed);
        }

        String toJson(String indent) {
            String pad = indent != null ? indent : "";
            StringBuilder sb = new StringBuilder(256);
            sb.append(pad).append("{\n");
            sb.append(pad).append("  \"combination\": ").append(toJsonString(combination)).append(",\n");
            sb.append(pad).append("  \"llmProvider\": ").append(toJsonString(llmProvider)).append(",\n");
            sb.append(pad).append("  \"embeddingProvider\": ").append(toJsonString(embeddingProvider)).append(",\n");
            sb.append(pad).append("  \"vectorDbProvider\": ").append(toJsonString(vectorDbProvider)).append(",\n");
            sb.append(pad).append("  \"durationMs\": ").append(durationMs).append(",\n");
            sb.append(pad).append("  \"testsFound\": ").append(testsFound).append(",\n");
            sb.append(pad).append("  \"testsSucceeded\": ").append(testsSucceeded).append(",\n");
            sb.append(pad).append("  \"testsFailed\": ").append(testsFailed).append(",\n");
            sb.append(pad).append("  \"testsSkipped\": ").append(testsSkipped).append(",\n");
            sb.append(pad).append("  \"testsAborted\": ").append(testsAborted).append(",\n");
            sb.append(pad).append("  \"consideredTests\": ").append(consideredTests()).append(",\n");
            sb.append(pad).append("  \"successRate\": ").append(String.format("%.4f", successRate)).append(",\n");
            sb.append(pad).append("  \"lastSnapshot\": ").append(lastSnapshot != null ? toJsonString(lastSnapshot) : "null").append(",\n");
            sb.append(pad).append("  \"extractionPath\": ").append(extractionPath != null ? toJsonString(extractionPath) : "null").append(",\n");
            sb.append(pad).append("  \"llmCalls\": ").append(llmCalls != null ? llmCalls : "null").append(",\n");
            sb.append(pad).append("  \"issueCodes\": ").append(toJsonStringList(issueCodes)).append(",\n");
            sb.append(pad).append("  \"normalizationRules\": ").append(toJsonStringList(normalizationRules)).append(",\n");
            sb.append(pad).append("  \"failures\": ").append(failures != null ? toJsonString(failures) : "null").append("\n");
            sb.append(pad).append("}");
            return sb.toString();
        }

        private static long safeCount(CountSupplier supplier) {
            try {
                return supplier.get();
            } catch (Throwable ignored) {
                return 0;
            }
        }

        @FunctionalInterface
        private interface CountSupplier {
            long get();
        }
    }

    private record AggregateScore(long testsSucceeded,
                                  long testsFailed,
                                  long testsSkipped,
                                  long testsAborted) {
        AggregateScore add(CombinationScore score) {
            return new AggregateScore(
                testsSucceeded + score.testsSucceeded(),
                testsFailed + score.testsFailed(),
                testsSkipped + score.testsSkipped(),
                testsAborted + score.testsAborted()
            );
        }

        long consideredTests() {
            return Math.max(0, testsSucceeded + testsFailed);
        }

        double successRate() {
            long considered = consideredTests();
            return considered == 0 ? 1.0d : ((double) testsSucceeded / (double) considered);
        }

        String toJson() {
            return "{"
                + "\"testsSucceeded\":" + testsSucceeded
                + ",\"testsFailed\":" + testsFailed
                + ",\"testsSkipped\":" + testsSkipped
                + ",\"testsAborted\":" + testsAborted
                + ",\"consideredTests\":" + consideredTests()
                + ",\"successRate\":" + String.format("%.4f", successRate())
                + "}";
        }
    }

    protected List<ProviderCombination> resolveProviderMatrix() {
        List<ProviderCombination> availableCombinations = availableProviderCombinations();

        // Log available combinations for debugging
        if (log.isDebugEnabled()) {
            log.debug("Available provider combinations ({}):", availableCombinations.size());
            availableCombinations.forEach(combo ->
                log.debug("  - {}", combo.displayName()));
        }

        String matrixSpec = System.getProperty(matrixPropertyKey());
        if (!StringUtils.hasText(matrixSpec)) {
            matrixSpec = System.getenv(matrixEnvVariable());
        }

        if (!StringUtils.hasText(matrixSpec)) {
            log.info("No matrix spec provided, using all {} available combinations", availableCombinations.size());
            return availableCombinations;
        }

        log.info("Parsing matrix spec: {}", matrixSpec);
        List<ProviderCombination> requestedCombinations = parseMatrixSpec(matrixSpec);

        // Validate each combination individually for better error messages
        for (ProviderCombination combo : requestedCombinations) {
            try {
                validateProviderCombination(combo);
            } catch (IllegalArgumentException e) {
                log.error("Invalid provider combination: {} - {}", combo.displayName(), e.getMessage());
                throw new IllegalArgumentException(
                    "Invalid provider combination in matrix spec: " + combo.displayName() + "\n" + e.getMessage(), e);
            }
        }

        validateRequestedCombinations(requestedCombinations, availableCombinations);
        return requestedCombinations;
    }

    protected List<ProviderCombination> availableProviderCombinations() {
        return discoverAvailableCombinations();
    }

    private List<ProviderCombination> discoverAvailableCombinations() {
        // Try to use provider registry first (new approach)
        try {
            ProviderRegistryService registry = ProviderRegistryService.getInstance();
            List<ProviderDefinition> availableLLM = registry.getAvailableLLMProviders();
            List<ProviderDefinition> availableEmbedding = registry.getAvailableEmbeddingProviders();

            if (!availableLLM.isEmpty() && !availableEmbedding.isEmpty()) {
                log.info("Using provider registry for discovery: {} LLM, {} embedding providers",
                    availableLLM.size(), availableEmbedding.size());

                List<String> llmProviders = availableLLM.stream()
                    .map(ProviderDefinition::getName)
                    .sorted()
                    .toList();

                List<String> embeddingProviders = availableEmbedding.stream()
                    .map(ProviderDefinition::getName)
                    .sorted()
                    .toList();

                List<String> vectorDbProviders = vectorDbProviders();

                List<ProviderCombination> combinations = new ArrayList<>();
                for (String llm : llmProviders) {
                    for (String embedding : embeddingProviders) {
                        for (String vector : vectorDbProviders) {
                            combinations.add(new ProviderCombination(llm, embedding, vector));
                        }
                    }
                }
                return combinations;
            }
        } catch (Exception e) {
            log.warn("Failed to use provider registry, falling back to Spring context discovery: {}", e.getMessage());
        }

        // Fallback to original Spring context discovery (backward compatibility)
        ProviderCombination defaultCombo = new ProviderCombination(defaultLlmProvider(), defaultEmbeddingProvider(), null);
        configureProviderProperties(defaultCombo);

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApplication.class)
            .profiles(discoveryProfile())
            .properties(defaultDiscoveryProperties())
            .properties(additionalDiscoveryProperties())
            .run()) {

            AIProviderManager providerManager = context.getBean(AIProviderManager.class);
            List<String> llmProviders = providerManager.getAvailableProviders().stream()
                .map(AIProvider::getProviderName)
                .sorted()
                .toList();

            List<String> embeddingProviders = context.getBeansOfType(EmbeddingProvider.class).values().stream()
                .filter(EmbeddingProvider::isAvailable)
                .map(EmbeddingProvider::getProviderName)
                .distinct()
                .sorted()
                .toList();

            List<String> vectorDbProviders = vectorDbProviders();

            List<ProviderCombination> combinations = new ArrayList<>();
            for (String llm : llmProviders) {
                for (String embedding : embeddingProviders) {
                    for (String vector : vectorDbProviders) {
                        combinations.add(new ProviderCombination(llm, embedding, vector));
                    }
                }
            }
            return combinations;
        } finally {
            clearProviderProperties(defaultCombo);
        }
    }

    private Map<String, Object> defaultDiscoveryProperties() {
        Map<String, Object> props = new java.util.HashMap<>(Map.of(
            "ai.providers.llm-provider", defaultLlmProvider(),
            "ai.providers.embedding-provider", defaultEmbeddingProvider()
        ));

        // Include API keys from environment for provider availability checks during discovery
        // Use both kebab-case and camelCase for Spring Boot relaxed binding
        String anthropicKey = System.getenv("ANTHROPIC_API_KEY");
        if (anthropicKey != null && !anthropicKey.trim().isEmpty()) {
            props.put("ai.providers.anthropic.api-key", anthropicKey);
            props.put("ai.providers.anthropic.apiKey", anthropicKey); // Also set camelCase
            props.put("ai.providers.anthropic.enabled", true); // Use boolean, not string
        }

        String openaiKey = System.getenv("OPENAI_API_KEY");
        if (openaiKey != null && !openaiKey.trim().isEmpty()) {
            props.put("ai.providers.openai.api-key", openaiKey);
            props.put("ai.providers.openai.apiKey", openaiKey); // Also set camelCase
            props.put("ai.providers.openai.enabled", true); // Use boolean, not string
        }

        String geminiKey = System.getenv("GEMINI_API_KEY");
        if (geminiKey != null && !geminiKey.trim().isEmpty()) {
            props.put("ai.providers.gemini.api-key", geminiKey);
            props.put("ai.providers.gemini.apiKey", geminiKey); // Also set camelCase
            props.put("ai.providers.gemini.enabled", true); // Use boolean, not string
        }

        String cohereKey = System.getenv("COHERE_API_KEY");
        if (cohereKey != null && !cohereKey.trim().isEmpty()) {
            props.put("ai.providers.cohere.api-key", cohereKey);
            props.put("ai.providers.cohere.apiKey", cohereKey); // Also set camelCase
            props.put("ai.providers.cohere.enabled", true); // Use boolean, not string
        }

        String azureKey = System.getenv("AZURE_API_KEY");
        String azureEndpoint = System.getenv("AZURE_ENDPOINT");
        if (azureKey != null && !azureKey.trim().isEmpty() && azureEndpoint != null && !azureEndpoint.trim().isEmpty()) {
            props.put("ai.providers.azure.api-key", azureKey);
            props.put("ai.providers.azure.apiKey", azureKey); // Also set camelCase
            props.put("ai.providers.azure.endpoint", azureEndpoint);
            props.put("ai.providers.azure.deployment-name", System.getenv("AZURE_DEPLOYMENT_NAME"));
            props.put("ai.providers.azure.embedding-deployment-name", System.getenv("AZURE_EMBEDDING_DEPLOYMENT_NAME"));
            props.put("ai.providers.azure.enabled", true); // Use boolean, not string
        }

        return props;
    }

    private List<ProviderCombination> parseMatrixSpec(String matrixSpec) {
        return Arrays.stream(matrixSpec.split(","))
            .map(String::trim)
            .filter(StringUtils::hasText)
            .map(entry -> {
                String[] parts = entry.split(":");
                if (parts.length < 2 || parts.length > 4) {
                    throw new IllegalArgumentException(
                        "Invalid provider matrix entry: '" + entry + "'. Expected llm:embedding[:vectordb] (optional deprecated ':storageStrategy' is ignored)");
                }

                String llm = parts[0].trim();
                String embedding = parts[1].trim();
                String vectorDb = parts.length >= 3 ? parts[2].trim() : null;
                if (parts.length == 4) {
                    String ignoredStorage = parts[3].trim();
                    if (StringUtils.hasText(ignoredStorage)) {
                        log.warn("Ignoring deprecated provider-matrix storage strategy segment '{}' in entry '{}'", ignoredStorage, entry);
                    }
                }

                if (!StringUtils.hasText(llm) || !StringUtils.hasText(embedding)) {
                    throw new IllegalArgumentException(
                        "Invalid provider matrix entry: '" + entry + "'. LLM and embedding providers cannot be empty.");
                }

                return new ProviderCombination(llm, embedding, vectorDb);
            })
            .collect(Collectors.toCollection(ArrayList::new));
    }

    private void validateRequestedCombinations(List<ProviderCombination> requested,
                                               List<ProviderCombination> available) {
        Set<ProviderCombination> availableSet = new LinkedHashSet<>(available);
        List<ProviderCombination> missing = requested.stream()
            .filter(combo -> !availableSet.contains(combo))
            .toList();

        if (!missing.isEmpty()) {
            // Enhanced error message with registry information
            StringBuilder errorMsg = new StringBuilder();
            errorMsg.append("Requested provider combinations are not available.\n\n");
            errorMsg.append("Missing combinations (").append(missing.size()).append("):\n");

            for (ProviderCombination combo : missing) {
                errorMsg.append("  ❌ ").append(combo.displayName()).append("\n");

                // Check individual providers and provide helpful messages
                String llmIssue = validateProvider(combo.llmProvider(), ProviderType.LLM);
                String embeddingIssue = validateProvider(combo.embeddingProvider(), ProviderType.EMBEDDING);

                if (llmIssue != null) {
                    errorMsg.append("     LLM Provider Issue: ").append(llmIssue).append("\n");
                }
                if (embeddingIssue != null) {
                    errorMsg.append("     Embedding Provider Issue: ").append(embeddingIssue).append("\n");
                }
            }

            errorMsg.append("\nAvailable combinations (").append(available.size()).append("):\n");
            available.stream()
                .limit(10) // Limit to first 10 to avoid overwhelming output
                .forEach(combo -> errorMsg.append("  ✅ ").append(combo.displayName()).append("\n"));
            if (available.size() > 10) {
                errorMsg.append("  ... and ").append(available.size() - 10).append(" more\n");
            }

            throw new IllegalArgumentException(errorMsg.toString());
        }
    }

    /**
     * Validate a single provider and return error message if invalid
     */
    private String validateProvider(String providerName, ProviderType type) {
        if (providerName == null || providerName.trim().isEmpty()) {
            return "Provider name is empty";
        }

        try {
            ProviderRegistryService registry = ProviderRegistryService.getInstance();
            ProviderDefinition def = registry.getProvider(providerName, type);

            if (def == null) {
                List<String> available = registry.getProviderNames(type);
                return String.format("Provider '%s' not found in registry. Available %s providers: %s",
                    providerName, type.name().toLowerCase(), String.join(", ", available));
            }

            if (!def.isEnabled()) {
                return String.format("Provider '%s' is disabled in registry", providerName);
            }

            if (!def.isAvailable()) {
                List<String> requiredVars = def.getRequiredEnvVars();
                if (!requiredVars.isEmpty()) {
                    List<String> missingVars = requiredVars.stream()
                        .filter(var -> System.getenv(var) == null || System.getenv(var).trim().isEmpty())
                        .toList();

                    if (!missingVars.isEmpty()) {
                        return String.format("Missing required environment variables: %s. " +
                            "Set these variables to use provider '%s'",
                            String.join(", ", missingVars), providerName);
                    }
                }
                return String.format("Provider '%s' is not available. Check configuration and environment variables",
                    providerName);
            }

            return null; // Provider is valid
        } catch (Exception e) {
            log.warn("Error validating provider {}: {}", providerName, e.getMessage());
            return "Error checking provider: " + e.getMessage();
        }
    }

    /**
     * Validate provider combination compatibility
     */
    private void validateProviderCombination(ProviderCombination combo) {
        List<String> issues = new ArrayList<>();

        // Check if providers exist in registry
        String llmIssue = validateProvider(combo.llmProvider(), ProviderType.LLM);
        if (llmIssue != null) {
            issues.add("LLM: " + llmIssue);
        }

        String embeddingIssue = validateProvider(combo.embeddingProvider(), ProviderType.EMBEDDING);
        if (embeddingIssue != null) {
            issues.add("Embedding: " + embeddingIssue);
        }

        // Check for known incompatibilities
        if (combo.llmProvider().equals("onnx") && combo.embeddingProvider().equals("onnx")) {
            // ONNX as LLM doesn't exist, but this is handled by validateProvider
        }

        if (!issues.isEmpty()) {
            throw new IllegalArgumentException("Provider combination validation failed for " + combo.displayName() + ":\n" +
                String.join("\n", issues));
        }
    }

    private void configureProviderProperties(ProviderCombination combination) {
        System.setProperty("LLM_PROVIDER", combination.llmProvider());
        System.setProperty("ai.providers.llm-provider", combination.llmProvider());
        System.setProperty("EMBEDDING_PROVIDER", combination.embeddingProvider());
        System.setProperty("ai.providers.embedding-provider", combination.embeddingProvider());

        if (StringUtils.hasText(combination.vectorDbProvider())) {
            System.setProperty(VECTORDB_PROPERTY, combination.vectorDbProvider());
        } else {
            System.clearProperty(VECTORDB_PROPERTY);
        }
    }

    private void clearProviderProperties(ProviderCombination combination) {
        System.clearProperty("LLM_PROVIDER");
        System.clearProperty("ai.providers.llm-provider");
        System.clearProperty("EMBEDDING_PROVIDER");
        System.clearProperty("ai.providers.embedding-provider");
        System.clearProperty(VECTORDB_PROPERTY);
        log.debug("Cleared provider properties at {}", Instant.now());
    }

    protected void beforeMatrixExecution() {
        cleanLuceneIndex();
    }

    private void cleanLuceneIndex() {
        Path indexPath = Path.of("data", "lucene-vector-index");
        if (!Files.exists(indexPath)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(indexPath)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // ignore
        }
    }

    protected String suiteDisplayName() {
        return getClass().getSimpleName();
    }

    protected String defaultLlmProvider() {
        return "openai";
    }

    protected String defaultEmbeddingProvider() {
        return "onnx";
    }

    protected String discoveryProfile() {
        return "real-api-test";
    }

    protected Map<String, Object> additionalDiscoveryProperties() {
        return Map.of();
    }

    protected abstract Class<?>[] suiteTestClasses();

    protected String matrixPropertyKey() {
        return "ai.providers.real-api.matrix";
    }

    protected String matrixEnvVariable() {
        return "AI_PROVIDERS_REAL_API_MATRIX";
    }

    protected record ProviderCombination(
        String llmProvider,
        String embeddingProvider,
        String vectorDbProvider
    ) {
        public ProviderCombination(String llmProvider, String embeddingProvider) {
            this(llmProvider, embeddingProvider, null);
        }

        public String displayName() {
            if (StringUtils.hasText(vectorDbProvider)) {
                return "LLM=" + llmProvider + " | Embedding=" + embeddingProvider + " | VectorDB=" + vectorDbProvider;
            }
            return "LLM=" + llmProvider + " | Embedding=" + embeddingProvider;
        }

        @Override
        public String toString() {
            if (StringUtils.hasText(vectorDbProvider)) {
                return llmProvider + "/" + embeddingProvider + "/" + vectorDbProvider;
            }
            return llmProvider + "/" + embeddingProvider;
        }
    }

    protected List<String> vectorDbProviders() {
        return java.util.Arrays.asList("lucene", null);
    }
}
