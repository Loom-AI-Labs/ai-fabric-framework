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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration coverage for TEST-EMBED-006: Large Text Chunking.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("onnx-test")
class EmbeddingLargeTextChunkingIntegrationTest {

    @Autowired
    private AIEmbeddingService embeddingService;

    @Autowired
    private EmbeddingProvider embeddingProvider;

    private String longProductNarrative;

    @BeforeEach
    void setUp() {
        assertNotNull(embeddingProvider, "Embedding provider must be available for chunking test");
        assertTrue(embeddingProvider.isAvailable(), "ONNX embedding provider should be initialized");
        assertEquals("onnx", embeddingProvider.getProviderName(), "Chunking test must run with ONNX provider");

        StringBuilder builder = new StringBuilder();
        String sentence = "The Hyperion premium travel membership delivers curated itineraries, private advisors, and experiential lounges worldwide. ";
        for (int i = 0; i < 120; i++) {
            builder.append(sentence).append("Segment ").append(i).append(" includes bespoke dining, wellness retreats, and cultural immersion. ");
        }
        longProductNarrative = builder.toString();
        assertTrue(longProductNarrative.length() > 8000, "Narrative should exceed 8K characters to trigger chunking");
    }

    @Test
    @DisplayName("chunkText splits long narratives into manageable segments and generates embeddings for each chunk")
    void chunkTextAndEmbedEachSegment() {
        int maxChunkSize = 1000;
        List<String> chunks = embeddingService.chunkText(longProductNarrative, maxChunkSize);

        assertTrue(chunks.size() > 1, "Chunking should produce multiple segments for long narratives");
        chunks.forEach(chunk -> assertTrue(chunk.length() <= maxChunkSize, "Each chunk must respect the configured max size"));

        // Validate representative coverage and total length
        assertTrue(chunks.getFirst().contains("Hyperion premium travel membership"),
            "First chunk should preserve the leading narrative");
        assertTrue(chunks.getLast().contains("Segment"),
            "Last chunk should include trailing segment markers");

        List<AIEmbeddingResponse> embeddings = embeddingService.generateEmbeddings(chunks, "luxury-experiences");
        assertEquals(chunks.size(), embeddings.size(), "Each chunk should have a corresponding embedding");

        embeddings.forEach(response -> {
            assertNotNull(response.getEmbedding(), "Embedding vector must not be null");
            assertFalse(response.getEmbedding().isEmpty(), "Embedding vector must contain values");
            assertEquals(response.getEmbedding().size(), response.getDimensions(),
                "Dimensions metadata should match vector length");
            assertEmbeddingValuesAreFinite(response.getEmbedding());
        });
    }

    private void assertEmbeddingValuesAreFinite(List<Double> embedding) {
        embedding.forEach(value -> {
            assertNotNull(value, "Embedding values must not contain nulls");
            assertFalse(value.isNaN(), "Embedding values must not contain NaN");
            assertFalse(value.isInfinite(), "Embedding values must not contain Infinity");
        });
    }
}
