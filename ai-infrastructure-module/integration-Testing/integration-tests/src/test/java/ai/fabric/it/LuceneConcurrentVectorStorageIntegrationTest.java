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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test implementation for TEST-VECTOR-003: Concurrent Vector Storage.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "ai.vector-db.lucene.index-path=./data/test-lucene-index/concurrent")
class LuceneConcurrentVectorStorageIntegrationTest {

    private static final String ENTITY_TYPE = "testproduct";
    private static final int THREAD_COUNT = 10;
    private static final int VECTORS_PER_THREAD = 10;
    private static final int TOTAL_VECTORS = THREAD_COUNT * VECTORS_PER_THREAD;

    @Autowired
    private AIEmbeddingService embeddingService;

    @Autowired
    private VectorManagementService vectorManagementService;

    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
    }

    @AfterEach
    void tearDown() {
        vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    @Test
    @DisplayName("Lucene vector database handles concurrent storage without corruption")
    void testConcurrentVectorStorage() throws InterruptedException, ExecutionException {
        executorService = Executors.newFixedThreadPool(THREAD_COUNT);
        List<Callable<Boolean>> tasks = new ArrayList<>(TOTAL_VECTORS);

        for (int threadIndex = 0; threadIndex < THREAD_COUNT; threadIndex++) {
            final int baseIndex = threadIndex * VECTORS_PER_THREAD;
            tasks.addAll(createStorageTasks(baseIndex));
        }

        List<Future<Boolean>> futures = executorService.invokeAll(tasks);
        executorService.shutdown();
        boolean terminated = executorService.awaitTermination(2, TimeUnit.MINUTES);
        assertTrue(terminated, "Executor service should terminate after completing storage tasks");

        for (Future<Boolean> future : futures) {
            assertTrue(future.get(), "Each vector storage task should complete successfully");
        }

        List<VectorRecord> vectors = vectorManagementService.getVectorsByEntityType(ENTITY_TYPE);
        assertEquals(TOTAL_VECTORS, vectors.size(),
            "Vector list for entity type should contain all stored vectors");

        for (int i = 0; i < TOTAL_VECTORS; i++) {
            String entityId = "product_concurrent_" + i;
            assertTrue(vectorManagementService.vectorExists(ENTITY_TYPE, entityId),
                () -> "Vector should exist for entity " + entityId);
        }
    }

    private List<Callable<Boolean>> createStorageTasks(int baseIndex) {
        List<Callable<Boolean>> tasks = new ArrayList<>(VECTORS_PER_THREAD);
        for (int i = 0; i < VECTORS_PER_THREAD; i++) {
            final int vectorIndex = baseIndex + i;
            tasks.add(() -> {
                String content = "Concurrent vector product " + vectorIndex + " description " + System.nanoTime();
                AIEmbeddingResponse embedding = embeddingService.generateEmbedding(
                    AIEmbeddingRequest.builder().text(content).build()
                );

                vectorManagementService.storeVector(
                    ENTITY_TYPE,
                    "product_concurrent_" + vectorIndex,
                    content,
                    embedding.getEmbedding(),
                    Map.of("batch", "concurrent")
                );
                return true;
            });
        }
        return tasks;
    }
}
