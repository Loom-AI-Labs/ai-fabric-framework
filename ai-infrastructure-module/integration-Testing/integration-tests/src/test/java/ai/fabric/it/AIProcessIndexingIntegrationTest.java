package ai.fabric.it;

import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.embedding.EmbeddingProvider;
import ai.fabric.it.entity.TestProduct;
import ai.fabric.it.service.TestProductService;
import ai.fabric.it.support.IndexingQueueTestSupport;
import ai.fabric.service.VectorManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = {
    TestApplication.class,
    AIProcessIndexingIntegrationTest.DeterministicEmbeddingProviderConfiguration.class,
    IndexingQueueTestSupport.class
})
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "test.searchable-vector-db.enabled=false", // Disable wrapper to test core behavior
    "ai.config.default-file=ai-entity-config-indexing-strategy.yml",
    "ai.indexing.enabled=true",
    "ai.indexing.async-worker.enabled=false",
    "ai.indexing.batch-worker.enabled=false",
    "ai.indexing.cleanup.enabled=false",
    "ai.providers.embedding-provider=onnx",
    "ai.providers.enable-fallback=false"
})
class AIProcessIndexingIntegrationTest {

    @Autowired
    private TestProductService productService;

    @Autowired
    private VectorManagementService vectorManagementService;

    @Autowired
    private IndexingQueueTestSupport indexingQueueTestSupport;

    @BeforeEach
    void setUp() {
        try {
            vectorManagementService.clearVectorsByEntityType("product");
        } catch (Exception ignored) {
            // Ignore if index doesn't exist
        }
    }

    @Test
    @DisplayName("Verify @AIProcess handles vector lifecycle: Create -> Update -> Delete")
    void verifyAIProcessLifecycle() {
        // 1. CREATE
        TestProduct product = TestProduct.builder()
            .name("Lifecycle Test Product")
            .description("Testing full vector lifecycle")
            .category("lifecycle")
            .brand("LifecycleBrand")
            .price(new BigDecimal("100.00"))
            .stockQuantity(10)
            .active(true)
            .build();

        TestProduct created = productService.createProduct(product);
        String entityId = created.getId().toString();

        // Manually drain queue and wait for async indexing (embedding generation can take time)
        // Using longer timeout for CI environments where resources may be constrained
        await().atMost(Duration.ofSeconds(60))
            .pollInterval(Duration.ofMillis(500))
            .until(() -> {
                indexingQueueTestSupport.drainQueue();
                return vectorManagementService.vectorExists("product", entityId);
            });

        assertTrue(vectorManagementService.vectorExists("product", entityId), "Vector should exist after creation");

        // 2. UPDATE
        productService.updateProduct(created.getId(), "Updated Lifecycle Product", "Updated description", null);

        // Manually drain queue and wait for vector update - check content (vector generation + update)
        await().atMost(Duration.ofSeconds(60))
            .pollInterval(Duration.ofMillis(500))
            .until(() -> {
                indexingQueueTestSupport.drainQueue();
                var vector = vectorManagementService.getVector("product", entityId);
                return vector.isPresent() && vector.get().getContent().contains("Updated description");
            });

        var updatedVector = vectorManagementService.getVector("product", entityId).orElseThrow();
        assertTrue(updatedVector.getContent().contains("Updated description"), "Vector content should be updated");

        // 3. DELETE
        productService.deleteProduct(created.getId());

        // Manually drain queue and wait for async deletion
        await().atMost(Duration.ofSeconds(60))
            .pollInterval(Duration.ofMillis(500))
            .until(() -> {
                indexingQueueTestSupport.drainQueue();
                return !vectorManagementService.vectorExists("product", entityId);
            });

        assertFalse(vectorManagementService.vectorExists("product", entityId), "Vector should be removed after deletion");
    }

    @TestConfiguration
    static class DeterministicEmbeddingProviderConfiguration {

        @Bean(name = "onnxEmbeddingProvider")
        @Primary
        EmbeddingProvider deterministicOnnxEmbeddingProvider() {
            return new DeterministicEmbeddingProvider();
        }
    }

    private static class DeterministicEmbeddingProvider implements EmbeddingProvider {

        private static final int DIMENSIONS = 384;

        @Override
        public String getProviderName() {
            return "onnx";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
            String text = request != null ? request.getText() : "";
            return responseFor(text);
        }

        @Override
        public List<AIEmbeddingResponse> generateEmbeddings(List<String> texts) {
            if (texts == null) {
                return List.of();
            }
            return texts.stream()
                .map(this::responseFor)
                .toList();
        }

        @Override
        public int getEmbeddingDimension() {
            return DIMENSIONS;
        }

        @Override
        public Map<String, Object> getStatus() {
            return Map.of(
                "provider", getProviderName(),
                "available", true,
                "dimensions", DIMENSIONS
            );
        }

        private AIEmbeddingResponse responseFor(String text) {
            return AIEmbeddingResponse.builder()
                .embedding(deterministicEmbedding(text))
                .model("test-onnx")
                .dimensions(DIMENSIONS)
                .processingTimeMs(1L)
                .requestId(UUID.randomUUID().toString())
                .build();
        }

        private static List<Double> deterministicEmbedding(String text) {
            int seed = text != null ? text.hashCode() : 0;
            List<Double> values = new ArrayList<>(DIMENSIONS);
            for (int i = 0; i < DIMENSIONS; i++) {
                int mixed = seed ^ (i * 0x9E3779B9);
                values.add(((mixed & 0xFF) / 255.0d) * 2.0d - 1.0d);
            }
            return values;
        }
    }
}
