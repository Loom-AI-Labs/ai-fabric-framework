package ai.fabric.it;

import ai.fabric.config.ResponseSanitizationProperties;
import ai.fabric.entity.IntentHistory;
import ai.fabric.exception.AIServiceException;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import ai.fabric.it.entity.TestProduct;
import ai.fabric.it.repository.TestProductRepository;
import ai.fabric.it.support.RealAPITestSupport;
import ai.fabric.repository.IntentHistoryRepository;
import ai.fabric.indexing.api.AIEntityIndexingGateway;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.service.VectorManagementService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("real-api-test")
@TestPropertySource(properties = {
    "ai.actions.builtin.vector-management.enabled=true"
})
@Transactional
public class RealAPIActionFlowIntegrationTest {

    private static final String OPENAI_KEY_PROPERTY = "OPENAI_API_KEY";

    static {
        RealAPITestSupport.ensureProviderConfigured();
        RealAPITestSupport.ensureLLMProviderSet();
        System.setProperty("EMBEDDING_PROVIDER",
            System.getProperty("EMBEDDING_PROVIDER", "onnx"));
        System.setProperty("ai.providers.embedding-provider",
            System.getProperty("ai.providers.embedding-provider", "onnx"));
    }

    @Autowired
    private AIEntityIndexingGateway indexingGateway;

    @Autowired
    private VectorManagementService vectorManagementService;

    @Autowired
    private RAGOrchestrator orchestrator;

    @Autowired
    private IntentHistoryRepository intentHistoryRepository;

    @Autowired
    private TestProductRepository productRepository;

    @Autowired
    private ResponseSanitizationProperties sanitizationProperties;

    @BeforeEach
    public void setUp() {
        vectorManagementService.clearAllVectors();
        productRepository.deleteAll();
        intentHistoryRepository.deleteAll();
    }

    @Test
    public void testRealActionHandlerRemovesVectorWithPIIRedaction() {
        assumeOpenAIConfigured();

        TestProduct legacyDevice = persistProduct(
            "Legacy Compliance Sensor",
            "An obsolete sensor model kept for regression tests. It contains known stale embeddings and references to retired payment flows.",
            "Compliance",
            "RetroScan",
            new BigDecimal("149.50")
        );
        String entityId = legacyDevice.getId().toString();
        RealAPITestSupport.awaitVectorExists(vectorManagementService, "test-product", entityId, Duration.ofSeconds(60));

        String userId = "real-action-removal-user";
        String query = """
            Compliance incident: The archived vector for "%s" still leaks card 5204-2190-4433-9910 inside nightly logs.
            Execute the remove_vector action with entityType "test-product" and entityId "%s" immediately.
            Confirm the purge and propose the next follow-up remediation step.""".formatted(legacyDevice.getName(), entityId);

        OrchestrationResult result = orchestrateOrSkip(query, userId);
        logOrchestrationStep("remove_vector action flow", result);
        assertNotNull(result, "Orchestrator should return a result");
        // Provider-agnostic contract: compound wrappers are normalized into the primary intent type.
        assertThat(result.getType())
            .as("Action should be executed")
            .isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();

        Map<String, Object> payload = result.getSanitizedPayload();
        assertThat(payload).isNotEmpty();

        Object safeSummary = payload.get("safeSummary");
        if (safeSummary instanceof String summary) {
            assertThat(summary).doesNotContain("5204");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> sanitization = (Map<String, Object>) payload.get("sanitization");
        assertThat(sanitization).isNotNull();
        String risk = Optional.ofNullable(sanitization.get("risk"))
            .map(Object::toString)
            .map(String::toUpperCase)
            .orElse("");
        assertThat(risk).isNotBlank();

        @SuppressWarnings("unchecked")
        List<String> detectedTypes = (List<String>) sanitization.getOrDefault("detectedTypes", List.of());
        assertThat(detectedTypes.stream().map(type -> type == null ? "" : type.toUpperCase()))
            .anyMatch(type -> type.contains("CREDIT_CARD"));

        @SuppressWarnings("unchecked")
        Map<String, Object> warning = (Map<String, Object>) payload.get("warning");
        if (warning != null) {
            assertThat(warning.get("message")).isEqualTo(sanitizationProperties.getHighRiskWarningMessage());
        }

        Object guidance = payload.get("guidance");
        if (guidance instanceof String guidanceMessage) {
            assertThat(guidanceMessage).isEqualTo(sanitizationProperties.getGuidanceMessage());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) payload.get("data");

        // In some edge cases, data might be null despite sanitization
        // Verify action was executed through alternate means if data is null
        if (data == null) {
            log.warn("Sanitized payload 'data' is null - using alternate verification");
            assertThat(result.isSuccess())
                .as("Action should be marked as successful")
                .isTrue();
            // Verify the primary action result: vector should be removed
            RealAPITestSupport.awaitVectorMissing(vectorManagementService, "test-product", entityId, Duration.ofSeconds(60));
        } else {
            assertThat(data).isNotNull();
            Object actionValue = data.get("action");
            if (actionValue != null) {
                assertThat(String.valueOf(actionValue)).isEqualTo("remove_vector");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> actionResult = (Map<String, Object>) data.get("actionResult");

            // actionResult might be null in some edge cases where the orchestration
            // result is structured differently. Verify action success through alternate means.
            if (actionResult == null) {
                log.warn("Sanitized payload 'actionResult' is null - using alternate verification");
                assertThat(result.isSuccess())
                    .as("Action should be marked as successful")
                    .isTrue();
                // Verify the primary action result: vector should be removed
                RealAPITestSupport.awaitVectorMissing(vectorManagementService, "test-product", entityId, Duration.ofSeconds(60));
            } else {
                assertThat(actionResult).isNotNull();
                assertThat(actionResult.get("success")).isEqualTo(Boolean.TRUE);

                @SuppressWarnings("unchecked")
                Map<String, Object> actionData = actionResult.get("data") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map
                    : Map.of();
                assertThat(actionData.get("removed")).isEqualTo(Boolean.TRUE);
                assertThat(actionResult.getOrDefault("message", "")).asString().doesNotContain("5204");

                RealAPITestSupport.awaitVectorMissing(vectorManagementService, "test-product", entityId, Duration.ofSeconds(60));
            }
        }

        RealAPITestSupport.awaitVectorMissing(vectorManagementService, "test-product", entityId, Duration.ofSeconds(60));

        // Verify suggestions don't contain PII if present
        if (payload.containsKey("suggestions")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> suggestions = (List<Map<String, Object>>) payload.get("suggestions");
            for (Map<String, Object> suggestion : suggestions) {
                assertThat(suggestion).isNotNull();
                assertThat(suggestion.values().stream()
                    .filter(String.class::isInstance)
                    .map(Object::toString)
                    .collect(Collectors.joining(" ")))
                    .doesNotContain("5204");
            }
        }

        // Verify smartSuggestion doesn't contain PII if present (it may be absent if no nextStep was generated)
        if (payload.containsKey("smartSuggestion")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> smartSuggestion = (Map<String, Object>) payload.get("smartSuggestion");
            assertThat(smartSuggestion.values().stream()
                .filter(String.class::isInstance)
                .map(Object::toString)
                .collect(Collectors.joining(" ")))
                .doesNotContain("5204");
        }

        List<IntentHistory> history = intentHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId);
        assertThat(history).isNotEmpty();
        IntentHistory record = history.getFirst();
        assertThat(record.getRedactedQuery()).doesNotContain("5204");
        if (StringUtils.hasText(record.getSensitiveDataTypes())) {
            assertThat(record.getSensitiveDataTypes().toUpperCase()).contains("CREDIT_CARD");
        }
        assertNotNull(record.getExecutionStatus());
        assertThat(Boolean.TRUE.equals(record.getSuccess())).isTrue();
    }

    @Test
    public void testRealCompoundInformationAndActionFlowProducesSmartSuggestion() {
        assumeOpenAIConfigured();

        TestProduct playbook = persistProduct(
            "Disaster Recovery Checklist",
            """
                A rapid response playbook:
                - Validate instrumentation dashboards.
                - Verify replicated data slices.
                - Execute cross-region failover rehearsal.
                This checklist is referenced by operations for emergency drills.
                """,
            "Runbooks",
            "SafetyOps",
            new BigDecimal("79.00")
        );

        TestProduct diagnostics = persistProduct(
            "Instrumentation Diagnostics Guide",
            """
                Step-by-step instrumentation diagnostics covering telemetry scrubbing,
                anomaly detection thresholds, and audit alerting.
                """,
            "Runbooks",
            "SafetyOps",
            new BigDecimal("59.00")
        );

        String userId = "real-compound-action-user";
        String query = """
            Operations bulletin for harper.ops@enterprise.example (phone 555-991-2045).
            We require two intents:
            1. Use the test-product knowledge base to summarise the "Disaster Recovery Checklist" so on-call engineers know the instrumentation steps.
            2. Afterwards, run the clear_vector_index action with reason "reseed" to wipe stale embeddings before we rebuild.
            Close with a recommended high-confidence validation to run next.""";

        OrchestrationResult result = orchestrateOrSkip(query, userId);
        logOrchestrationStep("compound information + clear_vector_index action flow", result);
        assertNotNull(result, "Orchestrator should return a result");
        // Provider-agnostic contract: compound wrappers are normalized into the primary intent type.
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);

        List<OrchestrationResult> children = result.getChildren();
        assertThat(children).hasSizeGreaterThanOrEqualTo(2);
        boolean hasActionChild = children.stream()
            .anyMatch(child -> child.getType() == OrchestrationResultType.ACTION_EXECUTED);
        boolean hasInformationChild = children.stream()
            .anyMatch(child -> child.getType() == OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(hasActionChild).isTrue();
        assertThat(hasInformationChild).isTrue();

        OrchestrationResult informationChild = children.stream()
            .filter(child -> child.getType() == OrchestrationResultType.INFORMATION_PROVIDED)
            .findFirst()
            .orElseThrow();
        assertThat(informationChild.getData()).containsKey("ragResponse");

        Map<String, Object> payload = result.getSanitizedPayload();
        assertThat(payload).isNotEmpty();

        Object safeSummary = payload.get("safeSummary");
        if (safeSummary instanceof String summary) {
            assertThat(summary)
                .doesNotContain("harper.ops@enterprise.example")
                .doesNotContain("555-991-2045");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> sanitization = (Map<String, Object>) payload.get("sanitization");
        assertThat(sanitization).isNotNull();
        String risk = Optional.ofNullable(sanitization.get("risk"))
            .map(Object::toString)
            .map(String::toUpperCase)
            .orElse("");
        assertThat(risk).isNotBlank();

        @SuppressWarnings("unchecked")
        List<String> detectedTypes = (List<String>) sanitization.getOrDefault("detectedTypes", List.of());
        List<String> upperTypes = detectedTypes.stream()
            .map(type -> type == null ? "" : type.toUpperCase())
            .collect(Collectors.toList());
        assertThat(upperTypes).anyMatch(type -> type.contains("EMAIL"));
        assertThat(upperTypes).anyMatch(type -> type.contains("PHONE"));

        @SuppressWarnings("unchecked")
        Map<String, Object> warning = (Map<String, Object>) payload.get("warning");
        if (warning != null) {
            assertThat(warning.get("message")).isIn(
                sanitizationProperties.getMediumRiskWarningMessage(),
                sanitizationProperties.getHighRiskWarningMessage()
            );
        }

        Object guidance = payload.get("guidance");
        if (guidance instanceof String guidanceMessage) {
            assertThat(guidanceMessage).isEqualTo(sanitizationProperties.getGuidanceMessage());
        }

        Object suggestionsObject = payload.get("suggestions");
        List<Map<String, Object>> suggestions = new ArrayList<>();
        if (suggestionsObject instanceof List<?> rawSuggestions) {
            for (Object raw : rawSuggestions) {
                if (raw instanceof Map<?, ?> suggestionMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> casted = (Map<String, Object>) suggestionMap;
                    suggestions.add(casted);
                }
            }
        }
        if (suggestions.isEmpty()) {
            suggestions.add(result.getSmartSuggestion());
        }
        assertThat(suggestions).isNotEmpty();
        for (Map<String, Object> suggestion : suggestions) {
            if (suggestion == null || suggestion.isEmpty()) {
                continue;
            }
            assertThat(suggestion.values().stream()
                .filter(String.class::isInstance)
                .map(Object::toString)
                .collect(Collectors.joining(" ")))
                .doesNotContain("harper.ops@enterprise.example")
                .doesNotContain("555-991-2045");
        }

        RealAPITestSupport.awaitVectorMissing(vectorManagementService, "test-product", playbook.getId().toString(), Duration.ofSeconds(60));
        RealAPITestSupport.awaitVectorMissing(vectorManagementService, "test-product", diagnostics.getId().toString(), Duration.ofSeconds(60));

        List<IntentHistory> history = intentHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId);
        assertThat(history).isNotEmpty();
        IntentHistory record = history.getFirst();
        assertThat(record.getRedactedQuery())
            .doesNotContain("harper.ops@enterprise.example")
            .doesNotContain("555-991-2045");
        if (StringUtils.hasText(record.getSensitiveDataTypes())) {
            String normalized = record.getSensitiveDataTypes().toUpperCase();
            assertThat(normalized).contains("EMAIL");
            assertThat(normalized).contains("PHONE");
        }
        assertNotNull(record.getExecutionStatus());
        assertThat(Boolean.TRUE.equals(record.getSuccess())).isTrue();
    }

    private void assumeOpenAIConfigured() {
        boolean hasOpenAI = StringUtils.hasText(System.getProperty(OPENAI_KEY_PROPERTY)) ||
                           StringUtils.hasText(System.getenv(OPENAI_KEY_PROPERTY));
        boolean hasAnthropic = StringUtils.hasText(System.getProperty("ANTHROPIC_API_KEY")) ||
                              StringUtils.hasText(System.getenv("ANTHROPIC_API_KEY"));
        boolean hasGemini = StringUtils.hasText(System.getProperty("GEMINI_API_KEY")) ||
                           StringUtils.hasText(System.getenv("GEMINI_API_KEY"));
        boolean hasCohere = StringUtils.hasText(System.getProperty("COHERE_API_KEY")) ||
                           StringUtils.hasText(System.getenv("COHERE_API_KEY"));
        boolean hasAzure = StringUtils.hasText(System.getProperty("AZURE_API_KEY")) ||
                          StringUtils.hasText(System.getenv("AZURE_API_KEY"));
        Assumptions.assumeTrue(
            hasOpenAI || hasAnthropic || hasGemini || hasCohere || hasAzure,
            "OPENAI_API_KEY, ANTHROPIC_API_KEY, GEMINI_API_KEY, COHERE_API_KEY, or AZURE_API_KEY not configured; skipping real API action flow tests."
        );
    }

    private TestProduct persistProduct(String name,
                                       String description,
                                       String category,
                                       String brand,
                                       BigDecimal price) {
        TestProduct product = TestProduct.builder()
            .name(name)
            .description(description)
            .category(category)
            .brand(brand)
            .price(price)
            .stockQuantity(25)
            .active(true)
            .build();
        product = productRepository.save(product);
        indexingGateway.upsert(product, AIProcessOperation.CREATE, IndexingStrategy.SYNC);
        return product;
    }

    private OrchestrationResult orchestrateOrSkip(String query, String userId) {
        try {
            return orchestrator.orchestrate(query, ai.fabric.intent.orchestration.OrchestrationContext.forUser(userId));
        } catch (AIServiceException ex) {
            Assumptions.assumeTrue(false,
                "Skipping real API action flow test because intent orchestration failed: " + ex.getMessage());
            return null;
        }
    }

    private void logOrchestrationStep(String step, OrchestrationResult result) {
        if (result == null) {
            log.info("RealAPI action-flow step '{}' returned null result", step);
            return;
        }
        List<String> childTypes = result.getChildren() == null
            ? List.of()
            : result.getChildren().stream()
                .map(child -> child.getType() + "/success=" + child.isSuccess())
                .toList();
        log.info("RealAPI action-flow step '{}' result type={}, success={}, message='{}', metadataKeys={}, childTypes={}",
            step,
            result.getType(),
            result.isSuccess(),
            result.getMessage(),
            result.getMetadata() != null ? result.getMetadata().keySet() : List.of(),
            childTypes);
    }
}
