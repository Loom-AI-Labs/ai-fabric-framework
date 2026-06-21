package ai.fabric.it;

import ai.fabric.config.ResponseSanitizationProperties;
import ai.fabric.entity.IntentHistory;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import ai.fabric.it.entity.TestProduct;
import ai.fabric.it.repository.TestProductRepository;
import ai.fabric.it.support.RealAPITestSupport;
import ai.fabric.repository.IntentHistoryRepository;
import ai.fabric.service.AICapabilityService;
import ai.fabric.service.VectorManagementService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("real-api-test")
@Transactional
public class RealAPISmartSuggestionsIntegrationTest {

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
    private AICapabilityService capabilityService;

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
    public void testSmartSuggestionsWithEnrichmentMetadataAndSanitization() {
        assumeOpenAIConfigured();

        // Create multiple products targeting different vector spaces and search contexts
        TestProduct product1 = persistProduct(
            "Advanced Threat Detection System",
            """
                Real-time threat detection and response platform for enterprise networks.
                Integrates with SIEM systems, correlates security events, and provides automated incident response.
                Features include machine learning-based anomaly detection, behavioral analytics, and threat hunting capabilities.
                """,
            "Security",
            "DefenseFirst",
            new BigDecimal("4999.99")
        );

        TestProduct product2 = persistProduct(
            "Network Traffic Analysis Suite",
            """
                Deep packet inspection and network flow analysis for complete visibility.
                Monitors bandwidth usage, identifies security threats, detects unauthorized access patterns.
                Supports threat intelligence integration and provides forensic capabilities for incident investigation.
                """,
            "Security",
            "NetGuard",
            new BigDecimal("3499.99")
        );

        TestProduct product3 = persistProduct(
            "Cloud Security Posture Management",
            """
                Continuous compliance monitoring across multi-cloud environments.
                Evaluates security configurations, identifies compliance gaps, and recommends remediation actions.
                Supports AWS, Azure, GCP, and on-premises deployments with unified dashboard.
                """,
            "Compliance",
            "CloudShield",
            new BigDecimal("2999.99")
        );

        String userId = "smart-suggestions-user";
        String query = """
            I need comprehensive security solutions for my enterprise.
            Please search the knowledge base for recommendations on advanced threat detection systems
            and their integration with network monitoring capabilities.
            Include best practices for cloud security posture management.
            """;

        OrchestrationResult result = orchestrator.orchestrate(query, ai.fabric.intent.orchestration.OrchestrationContext.forUser(userId));

        assertNotNull(result, "Orchestrator should return a result");
        assertThat(result.isSuccess()).isTrue();

        // Verify sanitized payload contains all expected metadata
        Map<String, Object> sanitizedPayload = result.getSanitizedPayload();
        assertThat(sanitizedPayload).isNotEmpty();
        assertThat(sanitizedPayload.get("success")).isEqualTo(Boolean.TRUE);

        // Verify sanitization metadata is present
        @SuppressWarnings("unchecked")
        Map<String, Object> sanitization = (Map<String, Object>) sanitizedPayload.get("sanitization");
        assertThat(sanitization).isNotNull();
        assertThat(sanitization.get("risk")).isNotNull();

        // Verify suggestions/recommendations are enriched with metadata
        Object suggestionsObj = sanitizedPayload.get("suggestions");
        if (suggestionsObj instanceof List<?> suggestions) {
            assertThat(suggestions).isNotEmpty()
                .as("Smart suggestions should be provided");

            for (Object suggestion : suggestions) {
                if (suggestion instanceof Map<?, ?> suggestionMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> sMap = (Map<String, Object>) suggestionMap;

                    // Verify enrichment metadata present
                    assertThat(sMap).containsKeys("intent", "confidence")
                        .as("Suggestion should have intent and confidence");

                    // Verify sanitization metadata propagated to suggestions
                    Object suggestSanitization = sMap.get("sanitization");
                    if (suggestSanitization != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> suggestSanit = (Map<String, Object>) suggestSanitization;
                        assertThat(suggestSanit).containsKey("risk")
                            .as("Suggestion should have sanitization risk metadata");
                    }
                }
            }
        }

        Object smartSuggestionObj = sanitizedPayload.get("smartSuggestion");
        if (smartSuggestionObj instanceof Map<?, ?> smartSugg) {
            @SuppressWarnings("unchecked")
            Map<String, Object> smartSuggestion = (Map<String, Object>) smartSugg;

            // Verify smart suggestion has enrichment fields
            assertThat(smartSuggestion).containsKeys("intent", "confidence")
                .as("Smart suggestion should contain intent and confidence");

            // Verify document alignment with searchable fields
            Object documents = smartSuggestion.get("documents");
            if (documents instanceof List<?> docsList) {
                assertThat(docsList).isNotEmpty()
                    .as("Smart suggestion should retrieve relevant documents");

                for (Object doc : docsList) {
                    if (doc instanceof Map<?, ?> docMap) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> document = (Map<String, Object>) docMap;

                        // Verify document fields match searchable content
                        Object content = document.get("content");
                        if (content instanceof String contentStr) {
                            String lowerContent = contentStr.toLowerCase();
                            boolean hasRelevantKeywords = lowerContent.contains("threat") ||
                                                        lowerContent.contains("security") ||
                                                        lowerContent.contains("detection") ||
                                                        lowerContent.contains("network");
                            assertThat(hasRelevantKeywords)
                                .as("Document should contain security/threat/detection keywords")
                                .isTrue();
                        }
                    }
                }
            }

            // Verify response is sanitized
            Object response = smartSuggestion.get("response");
            if (response instanceof String responseStr) {
                assertThat(responseStr).isNotEmpty();
                // Should not contain original PII if any was in the knowledge base
                assertThat(responseStr).doesNotContain("credit", "card", "api", "secret")
                    .as("Response should be sanitized");
            }
        }

        // Verify metadata about orchestration is captured
        Map<String, Object> metadata = result.getMetadata();
        if (metadata != null && !metadata.isEmpty()) {
            System.out.println("RealAPI smart-suggestions metadata keys: " + metadata.keySet());
            assertThat(metadata.get("intentsCount")).isNotNull()
                .as("Intent count should be recorded in metadata");
            assertThat(metadata.get("intentMetadata"))
                .as("Intent metadata should be recorded in metadata")
                .isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> intentMetadata = (Map<String, Object>) metadata.get("intentMetadata");
            assertThat(intentMetadata)
                .as("Intent metadata should include stable orchestration flags")
                .containsKey("requiresTargetResolution");
        }

        // Verify intent history captured the query
        List<IntentHistory> history = intentHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId);
        assertThat(history).isNotEmpty()
            .as("Intent history should capture the request");

        IntentHistory record = history.getFirst();
        assertThat(Boolean.TRUE.equals(record.getSuccess())).isTrue();
        assertNotNull(record.getExecutionStatus());
        assertThat(record.getRedactedQuery()).isNotEmpty();

        System.out.println("✅ Smart Suggestions Enrichment Test");
        System.out.println("   - Products created: 3");
        System.out.println("   - Suggestions provided: " + (suggestionsObj instanceof List ? ((List<?>) suggestionsObj).size() : "0"));
        System.out.println("   - Intent history records: " + history.size());
        System.out.println("   - Sanitization risk level: " + sanitization.get("risk"));
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
            "OPENAI_API_KEY, ANTHROPIC_API_KEY, GEMINI_API_KEY, COHERE_API_KEY, or AZURE_API_KEY not configured; skipping smart suggestions tests."
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
        capabilityService.processEntityForAI(product, "test-product");
        return product;
    }
}
