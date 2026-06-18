package ai.fabric.it;

import ai.fabric.dto.VectorRecord;
import ai.fabric.service.VectorManagementService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VectorUpdateDeleteIntegrationTest {

    private static final int VECTOR_DIMENSION = 24;
    private static final String INDEX_PATH = "./data/test-lucene-index/vector-update-" + UUID.randomUUID();

    @Autowired
    private VectorManagementService vectorManagementService;

    @DynamicPropertySource
    static void overrideIndexPath(DynamicPropertyRegistry registry) {
        registry.add("ai.vector-db.lucene.index-path", () -> INDEX_PATH);
    }

    @AfterEach
    void cleanupEntityState() {
        vectorManagementService.clearAllVectors();
    }

    @AfterAll
    void deleteIndexDirectory() throws IOException {
        Path path = Path.of(INDEX_PATH).toAbsolutePath().normalize();
        if (Files.exists(path)) {
            Files.walk(path)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(candidate -> {
                    try {
                        Files.deleteIfExists(candidate);
                    } catch (IOException ignored) {
                        // best-effort cleanup
                    }
                });
        }
    }

    @Test
    @DisplayName("Vector updates persist and deletes remove vectors")
    void vectorUpdateAndDeleteStayConsistent() {
        String entityType = "vector-update";
        String entityId = "entity-" + UUID.randomUUID();

        String initialVectorId = vectorManagementService.storeVector(
            entityType,
            entityId,
            "Original content",
            syntheticEmbedding(VECTOR_DIMENSION, 1),
            Map.of("version", 1, "origin", "initial")
        );

        VectorRecord initialVector = vectorManagementService.getVector(entityType, entityId)
            .orElseThrow(() -> new AssertionError("Initial vector should be retrievable"));
        assertEquals(initialVectorId, initialVector.getVectorId(), "Vector ID should match stored id");

        vectorManagementService.storeVector(
            entityType,
            entityId,
            "Updated content",
            syntheticEmbedding(VECTOR_DIMENSION, 2),
            Map.of("version", 2, "origin", "update")
        );

        VectorRecord updatedVector = vectorManagementService.getVector(entityType, entityId)
            .orElseThrow(() -> new AssertionError("Updated vector should be retrievable"));

        // Some providers update in place, others reinsert and return a new id.
        if (!initialVectorId.equals(updatedVector.getVectorId())) {
            assertTrue(vectorManagementService.getVectorById(initialVectorId).isEmpty(),
                "Old vector should not remain addressable after id-changing update");
        }
        assertEquals("Updated content", updatedVector.getContent(), "Content should reflect updated value");
        assertEquals("2", String.valueOf(updatedVector.getMetadata().get("version")), "Metadata version should be updated");
        assertEquals("update", String.valueOf(updatedVector.getMetadata().get("origin")), "Metadata origin should reflect update");

        vectorManagementService.removeVector(entityType, entityId);

        assertFalse(vectorManagementService.vectorExists(entityType, entityId), "Vector should be removed from vector store");
    }

    private List<Double> syntheticEmbedding(int dimension, int seed) {
        return java.util.stream.IntStream.range(0, dimension)
            .mapToDouble(index -> (seed + index + 1) * 0.001d)
            .boxed()
            .toList();
    }
}
