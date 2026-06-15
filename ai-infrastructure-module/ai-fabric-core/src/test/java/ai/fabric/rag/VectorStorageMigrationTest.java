package ai.fabric.rag;

import ai.fabric.config.TestConfiguration;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.VectorRecord;
import ai.fabric.service.VectorManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to verify the vector storage migration is working correctly.
 * This test verifies that vectors are stored in the vector database via {@link VectorManagementService},
 * with no relational searchable-entity cache involved.
 */
@SpringBootTest(classes = TestConfiguration.class)
@Import(TestConfiguration.class)
@ActiveProfiles("test")
public class VectorStorageMigrationTest {

    @Autowired
    private VectorManagementService vectorManagementService;

    @Test
    public void testVectorStorageMigration() {
        // Test data
        String entityType = "test-entity";
        String entityId = "test-123";
        String content = "This is a test document for vector storage migration";
        List<Double> embedding = Arrays.asList(0.1, 0.2, 0.3, 0.4, 0.5);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "test");
        metadata.put("version", "1.0");

        // Test storing a vector
        String vectorId = vectorManagementService.storeVector(entityType, entityId, content, embedding, metadata);
        assertNotNull(vectorId, "Vector ID should not be null");
        assertFalse(vectorId.isEmpty(), "Vector ID should not be empty");

        // Test retrieving the vector
        VectorRecord retrievedVector = vectorManagementService.getVectorById(vectorId).orElse(null);
        assertNotNull(retrievedVector, "Retrieved vector should not be null");
        assertEquals(entityType, retrievedVector.getEntityType(), "Entity type should match");
        assertEquals(entityId, retrievedVector.getEntityId(), "Entity ID should match");
        assertEquals(content, retrievedVector.getContent(), "Content should match");
        assertEquals(embedding, retrievedVector.getEmbedding(), "Embedding should match");
        assertNotNull(retrievedVector.getMetadata(), "Metadata should not be null");
        assertEquals("test", retrievedVector.getMetadata().get("source"), "Metadata field 'source' should match");
        assertEquals("1.0", retrievedVector.getMetadata().get("version"), "Metadata field 'version' should match");

        // Test searching vectors
        AISearchRequest searchRequest = AISearchRequest.builder()
                .query("test document")
                .entityType(entityType)
                .limit(10)
                .threshold(0.5)
                .build();

        AISearchResponse searchResponse = vectorManagementService.search(embedding, searchRequest);
        assertNotNull(searchResponse, "Search response should not be null");
        assertTrue(searchResponse.getTotalResults() > 0, "Should find at least one result");
        assertEquals(1, searchResponse.getResults().size(), "Should find exactly one result");

        // Test removing the vector
        boolean removed = vectorManagementService.removeVector(entityType, entityId);
        assertTrue(removed, "Vector should be removed successfully");

        // Verify vector is removed
        VectorRecord removedVector = vectorManagementService.getVectorById(vectorId).orElse(null);
        assertNull(removedVector, "Removed vector should not be found");
    }

    @Test
    public void testVectorExists() {
        String entityType = "test-entity";
        String entityId = "test-456";
        String content = "Another test document";
        List<Double> embedding = Arrays.asList(0.6, 0.7, 0.8, 0.9, 1.0);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "test");

        // Initially should not exist
        assertFalse(vectorManagementService.vectorExists(entityType, entityId), 
                   "Vector should not exist initially");

        // Store the vector
        String vectorId = vectorManagementService.storeVector(entityType, entityId, content, embedding, metadata);
        assertNotNull(vectorId);

        // Now should exist
        assertTrue(vectorManagementService.vectorExists(entityType, entityId), 
                  "Vector should exist after storing");

        // Clean up
        vectorManagementService.removeVector(entityType, entityId);
    }

    @Test
    public void testBatchOperations() {
        String entityType = "batch-test";
        List<VectorRecord> vectors = Arrays.asList(
                VectorRecord.builder()
                        .entityType(entityType)
                        .entityId("batch-1")
                        .content("First batch document")
                        .embedding(Arrays.asList(0.1, 0.2, 0.3))
                        .metadata(Map.of("batch", "1"))
                        .build(),
                VectorRecord.builder()
                        .entityType(entityType)
                        .entityId("batch-2")
                        .content("Second batch document")
                        .embedding(Arrays.asList(0.4, 0.5, 0.6))
                        .metadata(Map.of("batch", "2"))
                        .build()
        );

        // Test batch store
        List<String> vectorIds = vectorManagementService.batchStoreVectors(vectors);
        assertEquals(2, vectorIds.size(), "Should store 2 vectors");
        assertTrue(vectorIds.stream().allMatch(id -> id != null && !id.isEmpty()), 
                  "All vector IDs should be valid");

        // Test batch operations
        long count = vectorManagementService.getVectorCountByEntityType(entityType);
        assertEquals(2, count, "Should have 2 vectors for entity type");

        // Test batch remove
        int removedCount = vectorManagementService.batchRemoveVectors(vectorIds);
        assertEquals(2, removedCount, "Should remove 2 vectors");

        // Verify cleanup
        long finalCount = vectorManagementService.getVectorCountByEntityType(entityType);
        assertEquals(0, finalCount, "Should have 0 vectors after cleanup");
    }
}
