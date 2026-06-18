package ai.fabric.it;

import ai.fabric.core.AIEmbeddingService;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.embedding.EmbeddingProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test implementation for TEST-EMBED-005: Concurrent Embedding Generation.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("onnx-test")
class ONNXConcurrentEmbeddingIntegrationTest {

    private static final int CONCURRENT_REQUESTS = 50;
    private static final int THREAD_POOL_SIZE = 10;
    private static final long MAX_COMPLETION_MS = 60_000L; // 60 seconds target from test plan

    @Autowired
    private AIEmbeddingService embeddingService;

    @Autowired
    private EmbeddingProvider embeddingProvider;

    private ExecutorService executorService;

    @AfterEach
    void tearDown() {
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    @Test
    @DisplayName("ONNX embedding generation handles 50 concurrent requests without degradation")
    void testConcurrentEmbeddingGeneration() throws InterruptedException, ExecutionException {
        assertNotNull(embeddingProvider, "Embedding provider must be initialized");
        assertTrue(embeddingProvider.isAvailable(), "ONNX provider should be available for concurrent test");
        assertEquals("onnx", embeddingProvider.getProviderName(), "Concurrent test must run with ONNX provider");

        executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        List<Callable<AIEmbeddingResponse>> tasks = new ArrayList<>(CONCURRENT_REQUESTS);

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            final int index = i;
            tasks.add(() -> embeddingService.generateEmbedding(
                AIEmbeddingRequest.builder()
                    .text("Concurrent Product " + index + " description " + System.nanoTime())
                    .build()
            ));
        }

        long start = System.currentTimeMillis();
        List<Future<AIEmbeddingResponse>> futures = executorService.invokeAll(tasks);
        executorService.shutdown();
        boolean terminated = executorService.awaitTermination(2, TimeUnit.MINUTES);
        long duration = System.currentTimeMillis() - start;

        assertTrue(terminated, "Executor service should terminate after completing all tasks");
        assertTrue(duration < MAX_COMPLETION_MS,
            () -> "Concurrent embedding generation should finish within " + MAX_COMPLETION_MS + " ms but took " + duration + " ms");

        assertEquals(CONCURRENT_REQUESTS, futures.size(), "Should receive a future for each submitted task");

        for (Future<AIEmbeddingResponse> future : futures) {
            AIEmbeddingResponse response = future.get();
            assertNotNull(response, "Embedding response should not be null");
            assertNotNull(response.getEmbedding(), "Embedding vector should not be null");
            assertFalse(response.getEmbedding().isEmpty(), "Embedding vector should contain values");
            assertEquals(response.getEmbedding().size(), response.getDimensions(),
                "Dimensions should match the embedding vector length");
            assertEmbeddingValuesAreFinite(response.getEmbedding());
        }
    }

    private void assertEmbeddingValuesAreFinite(List<Double> embedding) {
        for (Double value : embedding) {
            assertNotNull(value, "Embedding values must not be null");
            assertFalse(value.isNaN(), "Embedding values must not be NaN");
            assertFalse(value.isInfinite(), "Embedding values must not be infinite");
        }
    }
}
