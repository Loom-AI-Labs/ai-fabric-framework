package ai.fabric.it;

import ai.fabric.core.AIEmbeddingService;
import ai.fabric.dto.AIEmbeddingRequest;
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
 * Integration coverage for TEST-EMBED-008: Special Characters Handling.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("onnx-test")
class EmbeddingSpecialCharactersIntegrationTest {

    @Autowired
    private AIEmbeddingService embeddingService;

    @Autowired
    private EmbeddingProvider embeddingProvider;

    @BeforeEach
    void verifyProvider() {
        assertNotNull(embeddingProvider, "Embedding provider must be available for special character test");
        assertTrue(embeddingProvider.isAvailable(), "ONNX embedding provider should be initialized");
        assertEquals("onnx", embeddingProvider.getProviderName(), "Test expects ONNX provider");
    }

    @Test
    @DisplayName("Embeddings gracefully handle unicode, emoji, and symbol heavy content")
    void embeddingsHandleSpecialCharacters() {
        String specialText = "✨ Premium café experience – dégustation spéciale ☕️ with emojis 💎 and symbols @#$%^&*() plus accents naïve, señorita, façade.";

        AIEmbeddingResponse response = embeddingService.generateEmbedding(
            AIEmbeddingRequest.builder().text(specialText).build()
        );

        assertNotNull(response, "Embedding response must not be null");
        assertNotNull(response.getEmbedding(), "Embedding vector must not be null");
        assertFalse(response.getEmbedding().isEmpty(), "Embedding vector must contain values");
        assertEmbeddingValuesAreFinite(response.getEmbedding());

        AIEmbeddingResponse repeatedResponse = embeddingService.generateEmbedding(
            AIEmbeddingRequest.builder().text(specialText).build()
        );

        assertEquals(response.getEmbedding(), repeatedResponse.getEmbedding(),
            "Deterministic provider should yield identical embeddings for identical special-character content");
    }

    private void assertEmbeddingValuesAreFinite(List<Double> embedding) {
        embedding.forEach(value -> {
            assertNotNull(value, "Embedding values must not contain nulls");
            assertFalse(value.isNaN(), "Embedding values must not contain NaN");
            assertFalse(value.isInfinite(), "Embedding values must not contain Infinity");
        });
    }
}
