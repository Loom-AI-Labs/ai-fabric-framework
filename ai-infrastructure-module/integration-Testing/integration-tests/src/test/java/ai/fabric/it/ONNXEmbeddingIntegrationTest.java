package ai.fabric.it;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.embedding.EmbeddingProvider;
import ai.fabric.provider.springai.SpringAiEmbeddingProvider;
import ai.fabric.provider.springai.SpringAiModelResolver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for ONNX embedding provider as described in TEST-EMBED-002.
 *
 * <p>This test verifies that the ONNX embedding pipeline works without invoking
 * external APIs while keeping semantic alignment with the OpenAI embedding service
 * used elsewhere in the platform.</p>
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("onnx-test")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = "sk-.*")
class ONNXEmbeddingIntegrationTest {

    private static final String TEST_TEXT = "AI-powered smart home automation system";
    private static final String OPENAI_EMBEDDING_MODEL = "text-embedding-3-small";
    private static final String SIMILAR_TEXT = "Smart home automation platform powered by AI for personalized control";

    @Autowired
    private AIEmbeddingService embeddingService;

    @Autowired
    private EmbeddingProvider embeddingProvider;

    @Test
    @DisplayName("ONNX embedding generation aligns with OpenAI semantic space")
    void testOnnxEmbeddingGenerationMatchesOpenAISemantics() {
        Assumptions.assumeTrue(embeddingProvider != null && embeddingProvider.isAvailable(),
            "ONNX embedding provider must be available for this test");

        long startTime = System.currentTimeMillis();
        AIEmbeddingResponse onnxResponse = embeddingService.generateEmbedding(
            AIEmbeddingRequest.builder()
                .text(TEST_TEXT)
                .build()
        );
        long duration = System.currentTimeMillis() - startTime;

        assertNotNull(onnxResponse, "Embedding response must not be null");
        assertNotNull(onnxResponse.getEmbedding(), "Embedding vector must not be null");
        assertFalse(onnxResponse.getEmbedding().isEmpty(), "Embedding vector must not be empty");
        assertEquals("onnx", embeddingProvider.getProviderName(), "Expected ONNX provider to be active");
        assertEquals(onnxResponse.getEmbedding().size(), onnxResponse.getDimensions(),
            "Dimensions metadata should match actual embedding size");
        assertTrue(duration < 1_000,
            () -> "Local ONNX inference should complete in under 1 second but took " + duration + "ms");
        assertEmbeddingValuesAreFinite(onnxResponse.getEmbedding());

        AIEmbeddingResponse onnxSimilarResponse = embeddingService.generateEmbedding(
            AIEmbeddingRequest.builder()
                .text(SIMILAR_TEXT)
                .build()
        );

        List<Double> openAiEmbedding = fetchOpenAIEmbedding(TEST_TEXT);
        List<Double> openAiSimilarEmbedding = fetchOpenAIEmbedding(SIMILAR_TEXT);

        double onnxSimilarity = cosineSimilarity(onnxResponse.getEmbedding(), onnxSimilarResponse.getEmbedding());
        double openAiSimilarity = cosineSimilarity(openAiEmbedding, openAiSimilarEmbedding);

        assertTrue(onnxSimilarity >= 0.55,
            () -> "Expected ONNX embeddings for related texts to be reasonably similar (>= 0.55) but was " + onnxSimilarity);
        assertTrue(openAiSimilarity >= 0.70,
            () -> "Expected OpenAI embeddings for related texts to be highly similar (>= 0.70) but was " + openAiSimilarity);

        double similarityDelta = Math.abs(onnxSimilarity - openAiSimilarity);
        assertTrue(similarityDelta <= 0.45,
            () -> "Expected ONNX similarity " + onnxSimilarity + " to be within 0.45 of OpenAI similarity "
                + openAiSimilarity);
    }

    private List<Double> fetchOpenAIEmbedding(String text) {
        String apiKey = System.getenv("OPENAI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
            "OPENAI_API_KEY environment variable must be defined");

        SpringAiModelResolver resolver = springAiOpenAiResolver(apiKey);
        try {
            SpringAiEmbeddingProvider provider = new SpringAiEmbeddingProvider("openai", resolver);
            AIEmbeddingResponse response = provider.generateEmbedding(AIEmbeddingRequest.builder()
                .text(text)
                .model(OPENAI_EMBEDDING_MODEL)
                .build());
            assertNotNull(response, "Spring AI OpenAI embedding response must not be null");
            assertNotNull(response.getEmbedding(), "Spring AI OpenAI embedding vector must not be null");
            assertFalse(response.getEmbedding().isEmpty(), "Spring AI OpenAI embedding result should contain data");
            return response.getEmbedding();
        } catch (RuntimeException runtimeException) {
            // Treat transient quota or service issues as a skipped comparison rather than a hard failure.
            Assumptions.assumeTrue(false,
                "Skipping Spring AI OpenAI embedding comparison due to API error: " + runtimeException.getMessage());
            throw runtimeException; // unreachable but required for compilation
        } finally {
            try {
                resolver.destroy();
            } catch (Exception ignored) {
                // Nothing actionable in tests; resolver cleanup is best-effort.
            }
        }
    }

    private SpringAiModelResolver springAiOpenAiResolver(String apiKey) {
        AIProviderConfig config = new AIProviderConfig();
        config.setEmbeddingProvider("openai");
        config.setEmbeddingApiKey(apiKey);
        config.getOpenai().setApiKey(apiKey);
        config.getOpenai().setEmbeddingModel(OPENAI_EMBEDDING_MODEL);
        return new SpringAiModelResolver(config);
    }

    private void assertEmbeddingValuesAreFinite(List<Double> embedding) {
        for (Double value : embedding) {
            assertNotNull(value, "Embedding values must not contain nulls");
            assertFalse(value.isNaN(), "Embedding values must not contain NaN");
            assertFalse(value.isInfinite(), "Embedding values must not contain Infinity");
        }
    }

    private double cosineSimilarity(List<Double> vectorA, List<Double> vectorB) {
        if (vectorA.size() != vectorB.size()) {
            throw new IllegalArgumentException("Vectors must have the same length to compute cosine similarity");
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < vectorA.size(); i++) {
            double a = vectorA.get(i);
            double b = vectorB.get(i);
            dotProduct += a * b;
            normA += a * a;
            normB += b * b;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
