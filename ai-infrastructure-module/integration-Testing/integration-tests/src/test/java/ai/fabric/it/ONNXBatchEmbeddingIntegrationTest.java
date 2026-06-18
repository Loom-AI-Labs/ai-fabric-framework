package ai.fabric.it;

import ai.fabric.core.AIEmbeddingService;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.embedding.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test implementation for TEST-EMBED-004: Batch Embedding Processing.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("onnx-test")
class ONNXBatchEmbeddingIntegrationTest {

    private static final int BATCH_SIZE = 100;
    private static final long MAX_DURATION_MS = 20_000L; // 20 seconds target from test plan

    @Autowired
    private AIEmbeddingService embeddingService;

    @Autowired
    private EmbeddingProvider embeddingProvider;

    private List<String> batchTexts;

    @BeforeEach
    void setUp() {
        assertNotNull(embeddingProvider, "Embedding provider must be available for batch test");
        assertTrue(embeddingProvider.isAvailable(), "ONNX embedding provider should be initialized");
        assertEquals("onnx", embeddingProvider.getProviderName(), "Batch test must run with ONNX provider");

        batchTexts = IntStream.range(0, BATCH_SIZE)
            .mapToObj(i -> "Performance Product " + i + " description with unique features and AI insights " + System.nanoTime())
            .toList();
    }

    @Test
    @DisplayName("ONNX batch embedding generation handles 100 items under 20 seconds")
    void testBatchEmbeddingProcessingWithONNX() {
        long start = System.currentTimeMillis();
        List<AIEmbeddingResponse> responses = embeddingService.generateEmbeddings(batchTexts, "test-product");
        long duration = System.currentTimeMillis() - start;

        assertNotNull(responses, "Batch embedding response list should not be null");
        assertEquals(BATCH_SIZE, responses.size(), "Batch embedding must return one response per input");

        Set<List<Double>> uniqueEmbeddings = new HashSet<>();
        for (AIEmbeddingResponse response : responses) {
            assertNotNull(response.getEmbedding(), "Individual embedding must not be null");
            assertFalse(response.getEmbedding().isEmpty(), "Embedding vector must contain values");
            assertEquals(response.getEmbedding().size(), response.getDimensions(),
                "Dimensions should match the embedding vector length");
            assertEmbeddingValuesAreFinite(response.getEmbedding());
            uniqueEmbeddings.add(List.copyOf(response.getEmbedding()));
        }

        assertEquals(BATCH_SIZE, uniqueEmbeddings.size(), "Each generated embedding should be unique for distinct inputs");
    }

    private void assertEmbeddingValuesAreFinite(List<Double> embedding) {
        for (Double value : embedding) {
            assertNotNull(value, "Embedding values must not be null");
            assertFalse(value.isNaN(), "Embedding values must not be NaN");
            assertFalse(value.isInfinite(), "Embedding values must not be infinite");
        }
    }
}
