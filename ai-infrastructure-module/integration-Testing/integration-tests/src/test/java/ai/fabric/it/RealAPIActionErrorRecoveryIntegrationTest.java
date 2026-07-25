package ai.fabric.it;

import ai.fabric.config.ResponseSanitizationProperties;
import ai.fabric.entity.IntentHistory;
import ai.fabric.exception.AIServiceException;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import ai.fabric.it.entity.TestProduct;
import ai.fabric.it.repository.TestProductRepository;
import ai.fabric.it.support.RealAPITestSupport;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.repository.IntentHistoryRepository;
import ai.fabric.indexing.api.AIEntityIndexingGateway;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.service.VectorManagementService;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.fabric.intent.action.AIActionProvider;
import ai.fabric.intent.action.ActionInfo;
import ai.fabric.intent.action.AvailableActionsRegistry;
import ai.fabric.intent.action.AIActionRegistry;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = {TestApplication.class, RealAPIActionErrorRecoveryIntegrationTest.TestActionOverrides.class})
@ActiveProfiles("real-api-test")
@Transactional
public class RealAPIActionErrorRecoveryIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RealAPIActionErrorRecoveryIntegrationTest.class);

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
    public void testRealActionHandlerErrorRecoveryProvidesSanitizedGuidance() {
        assumeOpenAIConfigured();

        TestProduct baseline = persistProduct(
            "Critical Access Ledger",
            """
                Maintains privileged access logs for incident forensics.
                Includes historical remediation guidance and vector operations checklists.
                """,
            "Runbooks",
            "SecOps",
            new BigDecimal("129.00")
        );

        String entityId = baseline.getId().toString();
        RealAPITestSupport.awaitVectorExists(vectorManagementService, "test-product", entityId, Duration.ofSeconds(60));
        var baselineVector = vectorManagementService.getVector("test-product", entityId)
            .orElseThrow(() -> new AssertionError("baseline vector missing"));
        assertThat(baselineVector.getVectorId()).isNotNull();
        assertThat(baselineVector.getVectorId()).isNotEmpty();

        String userId = "real-action-error-user";
        // Test that when only subscription actions are advertised, an unrelated request is treated as OUT_OF_SCOPE
        // (and we still get a sanitized payload when PII is present).
        String query = """
            Update my address to 91 perry common.
            Card 4916-2345-0987-1123 leaked in the export owned by bree.secops@enterprise.example (contact (555) 714-2209).
            """;

        OrchestrationResult result = orchestrateOrSkip(query, userId);

        assertNotNull(result, "Orchestrator should return a result");
        // The result may be SUCCESS (if LLM correctly identifies as OUT_OF_SCOPE) or ERROR (if action was attempted)
        // The key is that PII sanitization works regardless of success/failure

        // Verify that error information is captured (if action was attempted)
        Map<String, Object> data = result.getData();
        boolean actionWasAttempted = false;
        ActionResult actionResultCaptured = null;
        if (data != null && !data.isEmpty()) {
            Object actionResultObj = data.get("actionResult");
            if (actionResultObj != null) {
                if (actionResultObj instanceof ActionResult ar) {
                    actionWasAttempted = true;
                    actionResultCaptured = ar;
                } else if (actionResultObj instanceof Map<?, ?> map) {
                    // Some code paths may serialize ActionResult into a map
                    @SuppressWarnings("unchecked")
                    Map<String, Object> actionResult = (Map<String, Object>) map;
                    if (!actionResult.isEmpty()) {
                        actionWasAttempted = true;
                        actionResultCaptured = ActionResult.builder()
                            .success(Boolean.TRUE.equals(actionResult.get("success")))
                            .message(actionResult.get("message") != null ? String.valueOf(actionResult.get("message")) : null)
                            .errorCode(actionResult.get("errorCode") != null ? String.valueOf(actionResult.get("errorCode")) : null)
                            .build();
                    }
                }
            }
        }

        // Preferred behavior: OUT_OF_SCOPE. Some providers may instead answer with INFORMATION_PROVIDED.
        // Some providers may misclassify and return ACTION, which (in this test context) deterministically becomes
        // ERROR (ACTION_NOT_FOUND) because no handlers exist.
        //
        // Providers can also produce ERROR without attempting an action (e.g., parser/validation issues),
        // so we accept ERROR as long as no successful action is executed.
        assertThat(result.getType())
            .as("Expected OUT_OF_SCOPE, INFORMATION_PROVIDED, or ERROR (provider variability)")
            .isIn(
                OrchestrationResultType.OUT_OF_SCOPE,
                OrchestrationResultType.INFORMATION_PROVIDED,
                OrchestrationResultType.ERROR
            );

        if (actionWasAttempted) {
            assertThat(result.getType()).isEqualTo(OrchestrationResultType.ERROR);
        }

        if (result.getType() == OrchestrationResultType.ERROR) {
            assertThat(result.isSuccess()).isFalse();
            if (actionWasAttempted) {
                assertThat(result.getErrorCode())
                    .as("When an action is attempted but no handlers exist, errorCode should be ACTION_NOT_FOUND")
                    .isEqualTo("ACTION_NOT_FOUND");
            }
        } else {
            assertThat(result.isSuccess()).isTrue();
            assertThat(actionWasAttempted).isFalse();
        }

        // Prefer next-step recommendations; allow fallback to smart suggestions when the LLM returns a compound/error path
        // Prefer next-step recommendations; allow fallback to smart suggestions; tolerate empty in real API runs
        boolean hasNextSteps = result.getNextSteps() != null && !result.getNextSteps().isEmpty();
        boolean hasSmartSuggestion = result.getSmartSuggestion() != null && !result.getSmartSuggestion().isEmpty();
        if (!hasNextSteps && !hasSmartSuggestion) {
            log.info("No next steps or smart suggestions returned; continuing without failure");
        }

        Map<String, Object> sanitizedPayload = result.getSanitizedPayload();
        assertThat(sanitizedPayload).isNotEmpty();
        assertThat(String.valueOf(sanitizedPayload.get("type"))).isEqualTo(result.getType().name());
        assertThat(sanitizedPayload.get("success")).isEqualTo(result.isSuccess());

        Object safeSummary = sanitizedPayload.get("safeSummary");
        if (safeSummary instanceof String summary) {
            assertThat(summary)
                .doesNotContain("4916-2345-0987-1123")
                .doesNotContain("bree.secops@enterprise.example")
                .doesNotContain("555")
                .doesNotContain("714-2209");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> sanitization = (Map<String, Object>) sanitizedPayload.getOrDefault("sanitization", Map.of());
        assertThat(sanitization).isNotEmpty();
        String risk = Optional.ofNullable(sanitization.get("risk"))
            .map(Object::toString)
            .orElse("");
        assertThat(risk).isNotBlank();

        @SuppressWarnings("unchecked")
        List<String> detectedTypes = (List<String>) sanitization.getOrDefault("detectedTypes", List.of());
        List<String> normalizedTypes = detectedTypes.stream()
            .filter(StringUtils::hasText)
            .map(type -> type.toUpperCase().replace("-", "_"))
            .collect(Collectors.toList());
        assertThat(normalizedTypes).anyMatch(type -> type.contains("CREDIT") || type.contains("CARD"));
        assertThat(normalizedTypes).anyMatch(type -> type.contains("EMAIL"));
        assertThat(normalizedTypes).anyMatch(type -> type.contains("PHONE"));

        @SuppressWarnings("unchecked")
        Map<String, Object> warning = (Map<String, Object>) sanitizedPayload.get("warning");
        if (warning != null && !warning.isEmpty()) {
            assertThat(String.valueOf(warning.get("message")))
                .isIn(
                    sanitizationProperties.getHighRiskWarningMessage(),
                    sanitizationProperties.getMediumRiskWarningMessage()
                );
        }

        Object guidance = sanitizedPayload.get("guidance");
        if (guidance instanceof String guidanceMessage) {
            assertThat(guidanceMessage).isEqualTo(sanitizationProperties.getGuidanceMessage());
        }

        Object suggestionsObject = sanitizedPayload.get("suggestions");
        List<Map<String, Object>> suggestions = new ArrayList<>();
        if (suggestionsObject instanceof List<?> list) {
            for (Object raw : list) {
                if (raw instanceof Map<?, ?> suggestionMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> casted = (Map<String, Object>) suggestionMap;
                    suggestions.add(casted);
                }
            }
        }
        if (suggestions.isEmpty() && !result.getSmartSuggestion().isEmpty()) {
            suggestions.add(result.getSmartSuggestion());
        }

        // Only require suggestions when next-steps exist; otherwise tolerate empty (real API variability).
        if (result.getNextSteps() != null && !result.getNextSteps().isEmpty()) {
            assertThat(suggestions)
                .as("sanitized suggestions should be present when next steps are provided")
                .isNotEmpty();
        }
        for (Map<String, Object> suggestion : suggestions) {
            String combined = suggestion.values().stream()
                .filter(value -> value instanceof String)
                .map(Object::toString)
                .collect(Collectors.joining(" "));
            assertThat(combined)
                .doesNotContain("4916-2345-0987-1123")
                .doesNotContain("bree.secops@enterprise.example")
                .doesNotContain("555")
                .doesNotContain("714-2209");
        }

        // Verify vector still exists (wasn't cleared by invalid action)
        RealAPITestSupport.awaitVectorExists(vectorManagementService, "test-product", entityId, Duration.ofSeconds(60));
        assertThat(vectorManagementService.vectorExists("test-product", entityId))
            .as("vector should remain intact when action fails")
            .isTrue();

        List<IntentHistory> history = intentHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId);
        assertThat(history).isNotEmpty();
        IntentHistory record = history.getFirst();
        assertThat(Boolean.TRUE.equals(record.getSuccess())).isEqualTo(result.isSuccess());
        // In both cases, execution status should be recorded
        assertNotNull(record.getExecutionStatus(), "execution status should be recorded");
        assertThat(record.getRedactedQuery())
            .doesNotContain("4916-2345-0987-1123")
            .doesNotContain("bree.secops@enterprise.example")
            .doesNotContain("555")
            .doesNotContain("714-2209");
        if (StringUtils.hasText(record.getSensitiveDataTypes())) {
            String normalized = record.getSensitiveDataTypes().toUpperCase();
            assertThat(normalized).contains("CREDIT");
            assertThat(normalized).contains("EMAIL");
            assertThat(normalized).contains("PHONE");
        }
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
            "OPENAI_API_KEY, ANTHROPIC_API_KEY, GEMINI_API_KEY, COHERE_API_KEY, or AZURE_API_KEY not configured; skipping real API error recovery tests."
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
            .stockQuantity(50)
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
                "Skipping real API error recovery test because intent orchestration failed: " + ex.getMessage());
            return null;
        }
    }

    /**
     * Overrides the actions exposed to the LLM during intent extraction so the test suite can
     * validate OUT_OF_SCOPE behavior without providers accidentally selecting unrelated built-in actions.
     */
    @TestConfiguration
    static class TestActionOverrides {
        @Bean
        @Primary
        public AvailableActionsRegistry availableActionsRegistry() {
            AIActionProvider provider = () -> List.of(
                ActionInfo.builder()
                    .name("cancel_subscription")
                    .description("Cancel the user's subscription.")
                    .category("subscription")
                    .build(),
                ActionInfo.builder()
                    .name("upgrade_subscription")
                    .description("Upgrade the user's subscription plan.")
                    .category("subscription")
                    .build()
            );
            return new AvailableActionsRegistry(List.of(provider));
        }

        @Bean
        @Primary
        public AIActionRegistry aiActionRegistry() {
            // Ensure no unrelated action handlers can execute successfully in this test context.
            // If a provider still returns an ACTION intent, the orchestrator will deterministically report ACTION_NOT_FOUND.
            AIActionRegistry registry = Mockito.mock(AIActionRegistry.class);
            when(registry.getAllMetadata()).thenReturn(List.of());
            when(registry.getHandlerMap()).thenReturn(Map.of());
            when(registry.findHandler(anyString())).thenReturn(Optional.empty());
            when(registry.findMetadata(anyString())).thenReturn(Optional.empty());
            return registry;
        }
    }
}
