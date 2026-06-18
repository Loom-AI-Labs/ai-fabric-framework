package ai.fabric.it;

import ai.fabric.core.AIEmbeddingService;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.VectorRecord;
import ai.fabric.service.VectorManagementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test implementation for TEST-VECTOR-001: Basic Vector Storage.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "ai.vector-db.lucene.index-path=./data/test-lucene-index/basic")
class LuceneBasicVectorStorageIntegrationTest {

    private static final String ENTITY_TYPE = "testvector";
    private static final int VECTOR_COUNT = 10;
    private static final double FLOAT_TOLERANCE = 1e-4;

    @Autowired
    private AIEmbeddingService embeddingService;

    @Autowired
    private VectorManagementService vectorManagementService;

    @BeforeEach
    void setUp() {
        vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
    }

    @AfterEach
    void tearDown() {
        vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
    }

    @Test
    @DisplayName("Lucene vector database stores and retrieves vectors accurately")
    void testBasicVectorStorage() {
        List<List<Double>> storedEmbeddings = new ArrayList<>(VECTOR_COUNT);

        for (int i = 0; i < VECTOR_COUNT; i++) {
            String entityId = "vector_product_" + i;
            String content = "Vector storage test product " + i + " unique description " + System.nanoTime();

            AIEmbeddingResponse embeddingResponse = embeddingService.generateEmbedding(
                AIEmbeddingRequest.builder().text(content).build()
            );
            storedEmbeddings.add(embeddingResponse.getEmbedding());

            Map<String, Object> metadata = Map.of(
                "index", String.valueOf(i),
                "category", "basic-storage"
            );

            String vectorId = vectorManagementService.storeVector(
                ENTITY_TYPE,
                entityId,
                content,
                embeddingResponse.getEmbedding(),
                metadata
            );

            assertNotNull(vectorId, "Vector ID should be assigned for stored vector");
            assertTrue(vectorManagementService.vectorExists(ENTITY_TYPE, entityId),
                () -> "Vector should exist for entity " + entityId);

            Optional<VectorRecord> retrieved = vectorManagementService.getVector(ENTITY_TYPE, entityId);
            assertTrue(retrieved.isPresent(), "Stored vector should be retrievable");

            VectorRecord record = retrieved.get();
            assertEquals(vectorId, record.getVectorId(), "Retrieved vector ID should match stored ID");
            assertEquals(ENTITY_TYPE, record.getEntityType(), "Entity type should match");
            assertEquals(entityId, record.getEntityId(), "Entity ID should match");
            assertEquals(content, record.getContent(), "Stored content should match");
            String metadataRaw = record.getMetadata() != null ? (String) record.getMetadata().get("raw") : null;
            assertNotNull(metadataRaw, "Metadata should be stored alongside the vector");
            assertTrue(metadataRaw.contains("basic-storage"), "Metadata should include the category value");

            assertEmbeddingClose(storedEmbeddings.get(i), record.getEmbedding());
        }

        List<VectorRecord> allVectors = vectorManagementService.getVectorsByEntityType(ENTITY_TYPE);
        assertEquals(VECTOR_COUNT, allVectors.size(), "All vectors should be retrievable by entity type");
    }

    private void assertEmbeddingClose(List<Double> expected, List<Double> actual) {
        assertNotNull(actual, "Embedding should not be null");
        assertEquals(expected.size(), actual.size(), "Embedding dimension should match");
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), actual.get(i), FLOAT_TOLERANCE,
                "Embedding component mismatch at index " + i);
        }
    }
}
