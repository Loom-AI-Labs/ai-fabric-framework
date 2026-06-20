package ai.fabric.it;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.VectorRecord;
import ai.fabric.embedding.EmbeddingProvider;
import ai.fabric.it.entity.TestProduct;
import ai.fabric.it.repository.TestProductRepository;
import ai.fabric.service.AICapabilityService;
import ai.fabric.service.VectorManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = {
    TestApplication.class,
    MetadataFixTest.DeterministicEmbeddingProviderConfiguration.class
})
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "ai.vector-db.lucene.index-path=./data/test-lucene-index/metadata-fix",
    "ai.vector-db.lucene.similarity-threshold=0.0",
    "ai.providers.embedding-provider=onnx",
    "ai.providers.enable-fallback=false"
})
class MetadataFixTest {

    private static final String ENTITY_TYPE = "test-product";
    private static final List<Double> QUERY_VECTOR = List.of(1.0, 0.0, 0.0, 0.0);

    @Autowired
    private AICapabilityService capabilityService;

    @Autowired
    private AIEntityConfigurationLoader configurationLoader;

    @Autowired
    private TestProductRepository productRepository;

    @Autowired
    private VectorManagementService vectorManagementService;

    @BeforeEach
    void setUp() {
        vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
        productRepository.deleteAll();
    }

    @Test
    void configurationLoaderHasMetadataFields() {
        AIEntityConfig config = configurationLoader.getEntityConfig("test-product");
        assertNotNull(config, "Configuration should not be null");
        assertNotNull(config.getMetadataFields(), "Metadata fields should not be null");
        assertFalse(config.getMetadataFields().isEmpty(), "Metadata fields should not be empty");
        assertTrue(config.getMetadataFields().stream()
            .anyMatch(field -> "price".equals(field.getName()) && "DOUBLE".equalsIgnoreCase(field.getType())),
            "Price metadata should retain its configured numeric type");
    }

    @Test
    void processEntityForAIStoresConfiguredMetadataAndSupportsNumericFiltering() {
        TestProduct savedProduct = productRepository.save(TestProduct.builder()
            .name("Metadata Test Product")
            .description("Luxury metadata validation product")
            .category("Electronics")
            .price(new BigDecimal("99.99"))
            .brand("TestBrand")
            .active(true)
            .build());

        capabilityService.processEntityForAI(savedProduct, ENTITY_TYPE);

        String entityId = savedProduct.getId().toString();
        Optional<VectorRecord> stored = vectorManagementService.getVector(ENTITY_TYPE, entityId);
        assertTrue(stored.isPresent(), "Processing should store a vector for the entity");
        assertEquals("Electronics", stored.get().getMetadata().get("category"));
        assertEquals("TestBrand", stored.get().getMetadata().get("brand"));
        Number storedPrice = assertInstanceOf(Number.class, stored.get().getMetadata().get("price"));
        assertEquals(99.99d, storedPrice.doubleValue(), 0.0001d);

        AISearchRequest numericFilterRequest = AISearchRequest.builder()
            .query("metadata validation")
            .entityType(ENTITY_TYPE)
            .limit(5)
            .threshold(0.0)
            .metadata(Map.of("price", 99.99d))
            .build();

        AISearchResponse response = vectorManagementService.search(QUERY_VECTOR, numericFilterRequest);
        assertEquals(1, response.getResults().size(), "Numeric metadata filter should match the stored product");
        assertEquals(entityId, response.getResults().getFirst().get("id"));
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
            return response();
        }

        @Override
        public List<AIEmbeddingResponse> generateEmbeddings(List<String> texts) {
            if (texts == null) {
                return List.of();
            }
            return texts.stream().map(ignored -> response()).toList();
        }

        @Override
        public int getEmbeddingDimension() {
            return 4;
        }

        @Override
        public Map<String, Object> getStatus() {
            return Map.of("provider", getProviderName(), "available", true, "dimensions", 4);
        }

        private AIEmbeddingResponse response() {
            return AIEmbeddingResponse.builder()
                .embedding(new ArrayList<>(QUERY_VECTOR))
                .model("test-onnx")
                .dimensions(4)
                .processingTimeMs(1L)
                .requestId(UUID.randomUUID().toString())
                .build();
        }
    }
}
