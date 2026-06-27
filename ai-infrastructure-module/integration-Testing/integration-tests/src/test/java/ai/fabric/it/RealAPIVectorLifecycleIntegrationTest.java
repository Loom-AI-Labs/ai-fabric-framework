package ai.fabric.it;

import ai.fabric.config.ResponseSanitizationProperties;
import ai.fabric.entity.IntentHistory;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("real-api-test")
@TestPropertySource(properties = {
    "ai.actions.builtin.vector-management.enabled=true"
})
@Transactional
public class RealAPIVectorLifecycleIntegrationTest {

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
    public void testVectorLifecycleSynchronization() {
        assumeOpenAIConfigured();

        System.out.println("\n=== Phase 1: Create Entities with Vectors ===");

        TestProduct product1 = persistProduct(
            "Enterprise AI Analytics",
            """
                Real-time analytics platform powered by machine learning.
                Processes high-volume data streams with predictive insights.
                """,
            "Analytics",
            "DataFlow",
            new BigDecimal("5999.99")
        );

        TestProduct product2 = persistProduct(
            "Compliance Automation Suite",
            """
                Automates regulatory compliance workflows across departments.
                Generates audit trails and compliance reports automatically.
                """,
            "Compliance",
            "ComplianceHub",
            new BigDecimal("3999.99")
        );

        String productId1 = product1.getId().toString();
        String productId2 = product2.getId().toString();

        // Verify vectors exist after creation
        RealAPITestSupport.awaitVectorExists(vectorManagementService, "test-product", productId1, Duration.ofSeconds(60));
        RealAPITestSupport.awaitVectorExists(vectorManagementService, "test-product", productId2, Duration.ofSeconds(60));

        System.out.println("✅ Both vectors created successfully");

        System.out.println("\n=== Phase 2: Query with Vectors Present ===");

        String userId1 = "lifecycle-user-phase1";
        String query1 = "What analytics solutions do you offer?";

        OrchestrationResult result1 = orchestrator.orchestrate(query1, ai.fabric.intent.orchestration.OrchestrationContext.forUser(userId1));
        assertNotNull(result1);
        assertThat(result1.isSuccess()).isTrue();

        System.out.println("✅ Query executed successfully with vectors present");

        System.out.println("\n=== Phase 3: Execute remove_vector Action ===");

        String userId2 = "lifecycle-user-phase3";
        String removeQuery = String.format(
            "Execute the remove_vector action with entityType 'test-product' and entityId '%s'. Confirm removal.",
            productId1
        );

        OrchestrationResult removeResult = orchestrator.orchestrate(removeQuery, ai.fabric.intent.orchestration.OrchestrationContext.forUser(userId2));
        assertNotNull(removeResult);
        assertThat(removeResult.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);

        // Verify vector was removed
        RealAPITestSupport.awaitVectorMissing(vectorManagementService, "test-product", productId1, Duration.ofSeconds(60));
        assertThat(vectorManagementService.vectorExists("test-product", productId1))
            .as("Vector should be removed after remove_vector action")
            .isFalse();

        // Verify second vector still exists
        RealAPITestSupport.awaitVectorExists(vectorManagementService, "test-product", productId2, Duration.ofSeconds(60));
        assertThat(vectorManagementService.vectorExists("test-product", productId2))
            .as("Other vector should remain intact")
            .isTrue();

        System.out.println("✅ remove_vector action executed successfully");

        System.out.println("\n=== Phase 4: Execute clear_vector_index Action ===");

        String userId3 = "lifecycle-user-phase4";
        String clearQuery = "Execute the clear_vector_index action with reason 'reseed'. Clear all vectors.";

        OrchestrationResult clearResult = orchestrator.orchestrate(clearQuery, ai.fabric.intent.orchestration.OrchestrationContext.forUser(userId3));
        assertNotNull(clearResult);
        assertThat(clearResult.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);

        // Verify all vectors cleared
        RealAPITestSupport.awaitVectorMissing(vectorManagementService, "test-product", productId1, Duration.ofSeconds(60));
        RealAPITestSupport.awaitVectorMissing(vectorManagementService, "test-product", productId2, Duration.ofSeconds(60));
        assertThat(vectorManagementService.vectorExists("test-product", productId1))
            .as("Vector should be cleared")
            .isFalse();
        assertThat(vectorManagementService.vectorExists("test-product", productId2))
            .as("Vector should be cleared")
            .isFalse();

        System.out.println("✅ clear_vector_index action executed successfully");

        System.out.println("\n=== Phase 5: Rebuild Embeddings & Test Reseed ===");

        // Reprocess entities to rebuild vectors
        capabilityService.processEntityForAI(product1, "test-product");
        capabilityService.processEntityForAI(product2, "test-product");

        // Verify vectors exist again
        RealAPITestSupport.awaitVectorExists(vectorManagementService, "test-product", productId1, Duration.ofSeconds(60));
        RealAPITestSupport.awaitVectorExists(vectorManagementService, "test-product", productId2, Duration.ofSeconds(60));
        assertThat(vectorManagementService.vectorExists("test-product", productId1))
            .as("Vector should be rebuilt after reprocessing")
            .isTrue();
        assertThat(vectorManagementService.vectorExists("test-product", productId2))
            .as("Vector should be rebuilt after reprocessing")
            .isTrue();

        System.out.println("✅ Vectors rebuilt successfully");

        System.out.println("\n=== Phase 6: Query with Rebuilt Vectors ===");

        String userId4 = "lifecycle-user-phase6";
        String query2 = "Show me automation compliance solutions in your catalog.";

        OrchestrationResult result2 = orchestrator.orchestrate(query2, ai.fabric.intent.orchestration.OrchestrationContext.forUser(userId4));
        assertNotNull(result2);
        assertThat(result2.isSuccess()).isTrue();

        Map<String, Object> data = result2.getData();
        assertThat(data).isNotEmpty();

        System.out.println("✅ Query executed successfully with rebuilt vectors");

        System.out.println("\n=== Phase 7: Verify Intent History & TTL Tracking ===");

        List<IntentHistory> allHistory = intentHistoryRepository.findAll();
        assertThat(allHistory).isNotEmpty()
            .as("Intent history should record all operations")
            .hasSizeGreaterThanOrEqualTo(4);

        // Verify each phase recorded
        List<IntentHistory> phase1 = intentHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId1);
        List<IntentHistory> phase3 = intentHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId2);
        List<IntentHistory> phase4 = intentHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId3);
        List<IntentHistory> phase6 = intentHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId4);

        assertThat(phase1).isNotEmpty().as("Phase 1 (initial query) should be recorded");
        assertThat(phase3).isNotEmpty().as("Phase 3 (remove_vector) should be recorded");
        assertThat(phase4).isNotEmpty().as("Phase 4 (clear_vector_index) should be recorded");
        assertThat(phase6).isNotEmpty().as("Phase 6 (post-reseed query) should be recorded");

        // Verify execution statuses
        assertThat(phase1.getFirst().getSuccess()).isTrue();
        assertThat(phase3.getFirst().getSuccess()).isTrue();
        assertThat(phase4.getFirst().getSuccess()).isTrue();
        assertThat(phase6.getFirst().getSuccess()).isTrue();

        System.out.println("✅ Intent history properly tracked across all phases");

        System.out.println("\n=== Phase 8: Verify Lifecycle Transitions ===");

        System.out.println("📊 Vector Lifecycle Summary:");
        System.out.println("   1. ✅ Initial creation: 2 vectors");
        System.out.println("   2. ✅ Phase 1 query: vectors present");
        System.out.println("   3. ✅ Remove vector: 1 vector removed");
        System.out.println("   4. ✅ Clear index: all vectors cleared");
        System.out.println("   5. ✅ Reseed/rebuild: 2 vectors recreated");
        System.out.println("   6. ✅ Phase 6 query: rebuilt vectors used");
        System.out.println("   7. ✅ History tracking: 4+ phases recorded");
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
            "OPENAI_API_KEY, ANTHROPIC_API_KEY, GEMINI_API_KEY, COHERE_API_KEY, or AZURE_API_KEY not configured; skipping vector lifecycle tests."
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
            .stockQuantity(75)
            .active(true)
            .build();
        product = productRepository.save(product);
        capabilityService.processEntityForAI(product, "test-product");
        return product;
    }
}
