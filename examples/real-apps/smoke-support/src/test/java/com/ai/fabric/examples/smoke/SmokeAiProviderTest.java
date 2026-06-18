package com.ai.fabric.examples.smoke;

import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.provider.ProviderConfig;
import ai.fabric.provider.ProviderStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmokeAiProviderTest {

    private final SmokeAiProvider provider = new SmokeAiProvider();

    @Test
    void generatesDeterministicLocalResponseWithoutExternalUsage() {
        AIGenerationRequest request = AIGenerationRequest.builder()
            .entityId("product-42")
            .entityType("product")
            .generationType("summary")
            .prompt("Summarize this product")
            .build();

        AIGenerationResponse response = provider.generateContent(request);

        assertThat(response.getId()).startsWith("smoke-");
        assertThat(response.getRequestId()).isEqualTo("product-42");
        assertThat(response.getContent())
            .isEqualTo("[smoke profile] deterministic local response - no external model was called");
        assertThat(response.getModel()).isEqualTo("smoke");
        assertThat(response.getTokensUsed()).isZero();
        assertThat(response.getConfidence()).isZero();
        assertThat(response.getProcessingTimeMs()).isZero();
        assertThat(response.getStatus()).isEqualTo("OK");
        assertThat(response.getGeneratedAt()).isNotNull();
    }

    @Test
    void exposesOperationalStatusAndValidLocalConfig() {
        ProviderStatus status = provider.getStatus();
        ProviderConfig config = provider.getConfig();

        assertThat(provider.getProviderName()).isEqualTo("smoke");
        assertThat(provider.isAvailable()).isTrue();
        assertThat(status.isOperational()).isTrue();
        assertThat(status.getDetails()).isEqualTo("offline deterministic provider (smoke profile)");
        assertThat(config.isValid()).isTrue();
        assertThat(config.getApiKey()).isEqualTo("smoke-local-key");
        assertThat(config.getBaseUrl()).isEqualTo("smoke://local");
        assertThat(config.getDefaultModel()).isEqualTo("smoke");
        assertThat(config.getMaxRetries()).isZero();
    }

    @Test
    void delegatesEmbeddingGenerationToSmokeEmbeddingProvider() {
        AIEmbeddingResponse response = provider.generateEmbedding(AIEmbeddingRequest.builder()
            .text("same text")
            .build());

        assertThat(response.getModel()).isEqualTo("smoke");
        assertThat(response.getDimensions()).isEqualTo(SmokeEmbeddingProvider.DIMENSION);
        assertThat(response.getEmbedding()).hasSize(SmokeEmbeddingProvider.DIMENSION);
    }
}
