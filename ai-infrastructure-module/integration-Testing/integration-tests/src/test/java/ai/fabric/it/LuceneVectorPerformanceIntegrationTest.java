package ai.fabric.it;

import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.VectorRecord;
import ai.fabric.service.VectorManagementService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Disabled("Disabled in CI: Lucene sizing/perf benchmark exceeds allowed ONNX/Lucene/H2 runtime budget")
class LuceneVectorPerformanceIntegrationTest {

    private static final int VECTOR_DIMENSION = 32;
    private static final String INDEX_PATH = "./data/test-lucene-index/performance-" + UUID.randomUUID();

    @Autowired
    private VectorManagementService vectorManagementService;

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("ai.vector-db.lucene.index-path", () -> INDEX_PATH);
    }

    @AfterEach
    void tearDown() {
        vectorManagementService.clearAllVectors();
    }

    @AfterAll
    void cleanIndexDirectory() throws IOException {
        Path path = Path.of(INDEX_PATH).toAbsolutePath().normalize();
        if (Files.exists(path)) {
            Files.walk(path)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best effort cleanup
                    }
                });
        }
    }

    @Test
    @DisplayName("Vector update and deletion keep Lucene and repository states in sync")
    void vectorUpdateAndDeletionKeepsRepositoryInSync() {
        String entityType = "vector-lifecycle";
        String entityId = "entity-" + System.nanoTime();

        String initialVectorId = vectorManagementService.storeVector(entityType, entityId,
            "Initial performance vector",
            syntheticEmbedding(VECTOR_DIMENSION, 1), Map.of("version", 1));

        vectorManagementService.storeVector(entityType, entityId,
            "Updated performance vector", syntheticEmbedding(VECTOR_DIMENSION, 2), Map.of("version", 2));

        VectorRecord updatedVector = vectorManagementService.getVector(entityType, entityId)
            .orElseThrow(() -> new AssertionError("Updated vector should be retrievable"));

        assertEquals(initialVectorId, updatedVector.getVectorId(),
            "Vector ID should remain stable after in-place update");
        assertEquals("Updated performance vector", updatedVector.getContent());
        assertEquals("2", String.valueOf(updatedVector.getMetadata().get("version")));

        vectorManagementService.removeVector(entityType, entityId);

        assertFalse(vectorManagementService.vectorExists(entityType, entityId),
            "Vector should be removed from Lucene");
    }

    @Test
    @DisplayName("Lucene maintains stable memory usage while indexing 10K vectors and executing searches")
    void luceneMemoryUsageUnderLoad() {
        String entityType = "vector-memory-" + System.nanoTime();

        Runtime runtime = Runtime.getRuntime();
        invokeGc(runtime);
        long memoryBefore = usedMemoryBytes(runtime);

        indexVectors(entityType, 10_000, VECTOR_DIMENSION);

        invokeGc(runtime);
        long memoryAfter = usedMemoryBytes(runtime);
        long memoryDeltaMb = (memoryAfter - memoryBefore) / (1024 * 1024);
        assertTrue(memoryDeltaMb < 512,
            () -> "Lucene memory usage should remain below 512MB for 10K vectors but was " + memoryDeltaMb + "MB");

        List<Double> queryVector = syntheticEmbedding(VECTOR_DIMENSION, 5_000);
        long totalSearchNanos = 0L;
        for (int i = 0; i < 100; i++) {
            long start = System.nanoTime();
            AISearchResponse response = vectorManagementService.searchByEntityType(queryVector, entityType, 10, 0.0);
            assertNotNull(response, "Search response should not be null");
            totalSearchNanos += (System.nanoTime() - start);
        }

        long averageSearchMs = TimeUnit.NANOSECONDS.toMillis(totalSearchNanos / 100);
        assertTrue(averageSearchMs < 150,
            () -> "Average search latency should remain below 150ms but was " + averageSearchMs + "ms");

        long indexedCount = vectorManagementService.getVectorCountByEntityType(entityType);
        assertEquals(10_000, indexedCount, "All vectors should be indexed");
    }

    @Test
    @DisplayName("Lucene search latency scales within defined thresholds up to 100K vectors")
    void luceneSearchPerformanceScaling() {
        int[] datasetSizes = new int[]{100, 1_000, 10_000, 100_000};
        Map<Integer, Long> latencies = new LinkedHashMap<>();

        for (int size : datasetSizes) {
            String entityType = "vector-scale-" + size + "-" + System.nanoTime();
            indexVectors(entityType, size, 16);

            List<Double> queryVector = syntheticEmbedding(16, size / 2);
            long avgLatencyMs = measureAverageSearchLatency(entityType, queryVector, 10, 10);
            latencies.put(size, avgLatencyMs);

            long threshold = size <= 1_000 ? 80 : size <= 10_000 ? 160 : 600;
            assertTrue(avgLatencyMs <= threshold,
                () -> "Average search latency for dataset size " + size + " exceeded threshold " + threshold
                    + "ms with measured " + avgLatencyMs + "ms");

            vectorManagementService.clearVectorsByEntityType(entityType);
        }

        assertEquals(4, latencies.size(), "Latency map should capture all dataset sizes");
    }

    private void indexVectors(String entityType, int count, int dimension) {
        for (int i = 0; i < count; i++) {
            vectorManagementService.storeVector(entityType, entityType + "-" + i,
                "Synthetic performance content " + i,
                syntheticEmbedding(dimension, i), Map.of("batch", count));
        }
    }

    private long measureAverageSearchLatency(String entityType, List<Double> queryVector, int limit, int iterations) {
        long totalNanos = 0L;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            AISearchResponse response = vectorManagementService.searchByEntityType(queryVector, entityType, limit, 0.0);
            assertNotNull(response, "Search response should not be null");
            totalNanos += (System.nanoTime() - start);
        }
        return TimeUnit.NANOSECONDS.toMillis(totalNanos / iterations);
    }

    private List<Double> syntheticEmbedding(int dimension, int seed) {
        return java.util.stream.IntStream.range(0, dimension)
            .mapToDouble(i -> (seed + i + 1) * 0.0005d)
            .boxed()
            .toList();
    }

    private void invokeGc(Runtime runtime) {
        System.gc();
        System.runFinalization();
        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private long usedMemoryBytes(Runtime runtime) {
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
