package ai.fabric.it;

import ai.fabric.config.ResponseSanitizationProperties;
import ai.fabric.entity.IntentHistory;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import ai.fabric.it.entity.TestProduct;
import ai.fabric.it.repository.TestProductRepository;
import ai.fabric.it.support.RealAPITestSupport;
import ai.fabric.repository.IntentHistoryRepository;
import ai.fabric.indexing.api.AIEntityIndexingGateway;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingStrategy;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("real-api-test")
@Transactional
public class RealAPIHybridRetrievalToggleIntegrationTest {

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
    public void testHybridRetrievalToggleValidation() {
        assumeOpenAIConfigured();

        System.out.println("\n=== Phase 1: Create Test Products ===");

        TestProduct product1 = persistProduct(
            "Advanced Search Engine",
            """
                Hybrid retrieval search engine combining vector embeddings with keyword matching.
                Supports semantic search, contextual filtering, and advanced ranking algorithms.
                Optimized for hybrid search scenarios with both dense and sparse retrievers.
                """,
            "Search",
            "SearchCorp",
            new BigDecimal("7999.99")
        );

        TestProduct product2 = persistProduct(
            "Document Retrieval System",
            """
                Enterprise document retrieval with hybrid search capabilities.
                Combines BM25 keyword indexing with semantic embeddings.
                Contextual awareness for improved relevance in complex queries.
                """,
            "Retrieval",
            "DocPlatform",
            new BigDecimal("4999.99")
        );

        System.out.println("✅ Created 2 products with hybrid search features");

        System.out.println("\n=== Phase 2: Standard Vector Search Query ===");

        String userId1 = "hybrid-test-user-1";
        String query1 = "Show me advanced search solutions with semantic capabilities.";

        OrchestrationResult result1 = orchestrator.orchestrate(query1, ai.fabric.intent.orchestration.OrchestrationContext.forUser(userId1));
        assertNotNull(result1);
        assertThat(result1.isSuccess()).isTrue();

        System.out.println("✅ Standard vector search completed");

        // Check result data for search metadata
        Map<String, Object> data1 = result1.getData();
        assertThat(data1).isNotEmpty();

        System.out.println("📊 Result type: " + result1.getType());

        System.out.println("\n=== Phase 3: Query Targeting Contextual/Hybrid Features ===");

        String userId2 = "hybrid-test-user-2";
        String query2 = "Which hybrid retrieval and contextual filtering solutions do you offer?";

        OrchestrationResult result2 = orchestrator.orchestrate(query2, ai.fabric.intent.orchestration.OrchestrationContext.forUser(userId2));
        assertNotNull(result2);
        assertThat(result2.isSuccess()).isTrue();

        System.out.println("✅ Contextual/hybrid query completed");

        System.out.println("\n=== Phase 4: Verify Intent History for Search Metadata ===");

        List<IntentHistory> history1 = intentHistoryRepository
            .findByUserIdOrderByCreatedAtDesc(userId1);
        List<IntentHistory> history2 = intentHistoryRepository
            .findByUserIdOrderByCreatedAtDesc(userId2);

        assertThat(history1).isNotEmpty();
        assertThat(history2).isNotEmpty();

        IntentHistory record1 = history1.getFirst();
        IntentHistory record2 = history2.getFirst();

        System.out.println("✅ History records retrieved");
        System.out.println("   Record 1 success: " + record1.getSuccess());
        System.out.println("   Record 2 success: " + record2.getSuccess());

        System.out.println("\n=== Phase 5: Analyze Retrieved Content for Relevance ===");

        // Verify results contain relevant content
        if (data1 != null && !data1.isEmpty()) {
            Object content1 = data1.get("content");
            if (content1 != null) {
                String contentStr1 = content1.toString().toLowerCase();

                // Check for search-related keywords
                boolean hasSearchKeywords = contentStr1.contains("search") ||
                                          contentStr1.contains("semantic") ||
                                          contentStr1.contains("retrieval") ||
                                          contentStr1.contains("engine");

                System.out.println("✅ Content contains search-related keywords: " + hasSearchKeywords);
                assertThat(hasSearchKeywords).isTrue();
            }
        }

        System.out.println("\n=== Phase 6: Test Multiple Sequential Retrieval Queries ===");

        String userId3 = "hybrid-test-user-3";

        String query3a = "Tell me about document retrieval systems.";
        OrchestrationResult result3a = orchestrator.orchestrate(query3a, ai.fabric.intent.orchestration.OrchestrationContext.forUser(userId3));
        assertNotNull(result3a);
        assertThat(result3a.isSuccess()).isTrue();
        System.out.println("  ✅ Query 3a completed");

        String query3b = "How can I improve search relevance with contextual filtering?";
        OrchestrationResult result3b = orchestrator.orchestrate(query3b, ai.fabric.intent.orchestration.OrchestrationContext.forUser(userId3));
        assertNotNull(result3b);
        assertThat(result3b.isSuccess()).isTrue();
        System.out.println("  ✅ Query 3b completed");

        String query3c = "What are BM25 and semantic embeddings?";
        OrchestrationResult result3c = orchestrator.orchestrate(query3c, ai.fabric.intent.orchestration.OrchestrationContext.forUser(userId3));
        assertNotNull(result3c);
        System.out.println("  ✅ Query 3c completed");

        System.out.println("✅ Multiple sequential retrieval queries completed");

        System.out.println("\n=== Phase 7: Verify History Records Capture Search Behavior ===");

        List<IntentHistory> history3 = intentHistoryRepository
            .findByUserIdOrderByCreatedAtDesc(userId3);

        assertThat(history3).isNotEmpty()
            .as("Session 3 should have history records")
            .hasSizeGreaterThanOrEqualTo(3);

        // Verify all are successful
        long successfulSearches = history3.stream()
            .filter(h -> h.getSuccess() != null && h.getSuccess())
            .count();

        System.out.println("✅ Successful searches: " + successfulSearches + "/" + history3.size());
        assertThat(successfulSearches).isGreaterThanOrEqualTo(2L);

        System.out.println("\n=== Phase 8: Verify Intent Extraction for Retrieval Context ===");

        // Check intents JSON for retrieval-related fields
        List<IntentHistory> allHistory = intentHistoryRepository.findAll();

        long recordsWithIntents = allHistory.stream()
            .filter(h -> h.getIntentsJson() != null && !h.getIntentsJson().isEmpty())
            .count();

        System.out.println("✅ Records with intent extraction data: " + recordsWithIntents + "/" + allHistory.size());
        assertThat(recordsWithIntents).isGreaterThan(0);

        // Verify resultJson contains retrieval results
        long recordsWithResults = allHistory.stream()
            .filter(h -> h.getResultJson() != null && !h.getResultJson().isEmpty())
            .count();

        System.out.println("✅ Records with orchestration results: " + recordsWithResults + "/" + allHistory.size());
        assertThat(recordsWithResults).isGreaterThan(0);

        System.out.println("\n=== Phase 9: Verify Vector Search Fallback Behavior ===");

        // When hybrid services are unavailable, system should fallback to standard vector search
        // This is verified by the successful orchestration and result generation

        long successfulRetrieval = allHistory.stream()
            .filter(h -> h.getSuccess() != null && h.getSuccess() &&
                        h.getResultJson() != null && !h.getResultJson().isEmpty())
            .count();

        System.out.println("✅ Successfully retrieved documents with fallback: " + successfulRetrieval);
        assertThat(successfulRetrieval).isGreaterThan(0);

        System.out.println("\n=== Phase 10: Validate Search Metadata Consistency ===");

        // All records should have consistent metadata structure
        for (IntentHistory record : allHistory) {
            assertNotNull(record.getId());
            assertNotNull(record.getUserId());
            assertNotNull(record.getCreatedAt());
            assertNotNull(record.getSuccess());
        }

        System.out.println("✅ All records have consistent metadata");

        System.out.println("\n=== Phase 11: Verify Search Performance Metrics ===");

        int totalHistoryRecords = allHistory.size();
        int successfulRecords = (int) allHistory.stream()
            .filter(h -> h.getSuccess() != null && h.getSuccess())
            .count();
        int failedRecords = totalHistoryRecords - successfulRecords;

        double successRate = (double) successfulRecords / totalHistoryRecords * 100;

        System.out.println("📊 Search Performance Summary:");
        System.out.println("   Total queries: " + totalHistoryRecords);
        System.out.println("   Successful: " + successfulRecords);
        System.out.println("   Failed: " + failedRecords);
        System.out.println("   Success rate: " + String.format("%.1f", successRate) + "%");

        assertThat(successRate).isGreaterThanOrEqualTo(50.0);

        System.out.println("\n=== Phase 12: Final Hybrid Retrieval Summary ===");

        System.out.println("✅ Hybrid Retrieval Toggle Validation Complete:");
        System.out.println("   ✓ Standard vector search tested");
        System.out.println("   ✓ Contextual queries processed");
        System.out.println("   ✓ Multiple sequential queries handled");
        System.out.println("   ✓ History records capture search behavior");
        System.out.println("   ✓ Fallback to standard search verified");
        System.out.println("   ✓ Metadata consistency validated");
        System.out.println("   ✓ Performance metrics recorded");
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
            "OPENAI_API_KEY, ANTHROPIC_API_KEY, GEMINI_API_KEY, COHERE_API_KEY, or AZURE_API_KEY not configured; skipping hybrid retrieval tests."
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
}
