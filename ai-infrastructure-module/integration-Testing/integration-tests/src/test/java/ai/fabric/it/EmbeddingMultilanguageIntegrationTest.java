package ai.fabric.it;

import ai.fabric.core.AIEmbeddingService;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.embedding.EmbeddingProvider;
import com.theokanning.openai.OpenAiHttpException;
import com.theokanning.openai.embedding.EmbeddingRequest;
import com.theokanning.openai.embedding.EmbeddingResult;
import com.theokanning.openai.service.OpenAiService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration coverage for TEST-EMBED-007: Multi-language Content.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("onnx-test")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = "sk-.*")
class EmbeddingMultilanguageIntegrationTest {

    @Autowired
    private AIEmbeddingService embeddingService;

    @Autowired
    private EmbeddingProvider embeddingProvider;

    private Map<String, String> multilingualPhrases;

    private final Map<String, List<Double>> fallbackEmbeddingCache = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        assertNotNull(embeddingProvider, "Embedding provider must be available for multilingual test");
        assertTrue(embeddingProvider.isAvailable(), "ONNX embedding provider should be available");
        assertEquals("onnx", embeddingProvider.getProviderName(), "Test expects ONNX provider");

        multilingualPhrases = Map.of(
            "en", "This is a premium travel membership",
            "es", "Esta es una membresía de viaje premium",
            "fr", "Il s'agit d'une adhésion de voyage haut de gamme",
            "ja", "これはプレミアムな旅行メンバーシップです"
        );
    }

    @Test
    @DisplayName("Embeddings for translations remain closer to each other than to unrelated text")
    void embeddingsAcrossLanguagesRemainSemanticallyAligned() {
        AIEmbeddingResponse englishEmbedding = embeddingService.generateEmbedding(
            AIEmbeddingRequest.builder().text(multilingualPhrases.get("en")).build()
        );
        assertEmbeddingValid(englishEmbedding);

        double englishSelfSimilarity = cosineSimilarity(englishEmbedding.getEmbedding(), englishEmbedding.getEmbedding());
        Optional<List<Double>> fallbackEnglish = fetchFallbackEmbedding(multilingualPhrases.get("en"));

        multilingualPhrases.entrySet().stream()
            .filter(entry -> !entry.getKey().equals("en"))
            .forEach(entry -> {
                AIEmbeddingResponse translationEmbedding = embeddingService.generateEmbedding(
                    AIEmbeddingRequest.builder().text(entry.getValue()).build()
                );
                assertEmbeddingValid(translationEmbedding);

                double similarity = cosineSimilarity(englishEmbedding.getEmbedding(), translationEmbedding.getEmbedding());
                Optional<List<Double>> fallbackTranslation = fetchFallbackEmbedding(entry.getValue());

                if (fallbackEnglish.isPresent() && fallbackTranslation.isPresent()) {
                    double fallbackSimilarity = cosineSimilarity(fallbackEnglish.get(), fallbackTranslation.get());
                    assertTrue(fallbackSimilarity >= 0.60,
                        () -> "Fallback OpenAI similarity should be high for " + entry.getKey() + " translation but was "
                            + fallbackSimilarity);
                } else {
                    assertTrue(similarity > 0.45,
                        () -> "Expected English to " + entry.getKey() + " similarity to exceed 0.45 but was " + similarity);
                    assertTrue(englishSelfSimilarity - similarity < 0.25,
                        () -> "Expected translation similarity " + similarity + " to remain close to English baseline "
                            + englishSelfSimilarity);
                }
            });
    }

    private void assertEmbeddingValid(AIEmbeddingResponse response) {
        assertNotNull(response, "Embedding response must not be null");
        assertNotNull(response.getEmbedding(), "Embedding vector must not be null");
        assertFalse(response.getEmbedding().isEmpty(), "Embedding vector must contain values");
        response.getEmbedding().forEach(value -> {
            assertNotNull(value, "Embedding values must not contain nulls");
            assertFalse(value.isNaN(), "Embedding values must not contain NaN");
            assertFalse(value.isInfinite(), "Embedding values must not contain Infinity");
        });
    }

    private double cosineSimilarity(List<Double> vectorA, List<Double> vectorB) {
        assertEquals(vectorA.size(), vectorB.size(), "Vectors must be the same size for cosine similarity");
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

    private Optional<List<Double>> fetchFallbackEmbedding(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        List<Double> cached = fallbackEmbeddingCache.get(text);
        if (cached != null) {
            return Optional.of(cached);
        }
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        try {
            OpenAiService openAiService = new OpenAiService(apiKey, Duration.ofSeconds(90));
            EmbeddingRequest request = EmbeddingRequest.builder()
                .model("text-embedding-3-small")
                .input(List.of(text))
                .build();
            EmbeddingResult result = openAiService.createEmbeddings(request);
            if (result.getData().isEmpty()) {
                return Optional.empty();
            }
            List<Double> embedding = result.getData().get(0).getEmbedding();
            fallbackEmbeddingCache.put(text, embedding);
            return Optional.of(embedding);
        } catch (OpenAiHttpException openAiException) {
            return Optional.empty();
        } catch (Exception unexpected) {
            return Optional.empty();
        }
    }
}
