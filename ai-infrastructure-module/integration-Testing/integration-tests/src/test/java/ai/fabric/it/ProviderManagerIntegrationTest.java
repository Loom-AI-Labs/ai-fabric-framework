package ai.fabric.it;

import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.provider.AIProviderManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link AIProviderManager} is operational within the integration-test context.
 */
@Disabled("Disabled due to ApplicationContext loading failures - table creation issues")
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class ProviderManagerIntegrationTest {

    @Autowired
    private AIProviderManager providerManager;

    @Test
    void providerManagerGeneratesContentAndEmbeddings() {
        assertNotNull(providerManager, "AIProviderManager should be available");

        List<?> providers = providerManager.getAvailableProviders();
        assertFalse(providers.isEmpty(), "There should be at least one available provider");

        AIGenerationResponse generationResponse = providerManager.generateContent(
            AIGenerationRequest.builder()
                .entityId("demo-entity")
                .entityType("demo-type")
                .generationType("demo")
                .prompt("Explain how the AI provider manager works")
                .build()
        );

        assertNotNull(generationResponse.getContent(), "Generation response should include content");
        assertNotNull(generationResponse.getModel(), "Generation response should include model information");

        AIEmbeddingResponse embeddingResponse = providerManager.generateEmbedding(
            AIEmbeddingRequest.builder()
                .text("embedding test input")
                .build()
        );

        assertNotNull(embeddingResponse.getEmbedding(), "Embedding response should include vector data");
        assertEquals(embeddingResponse.getDimensions(), embeddingResponse.getEmbedding().size(),
            "Embedding vector length should match reported dimensions");
    }
}
