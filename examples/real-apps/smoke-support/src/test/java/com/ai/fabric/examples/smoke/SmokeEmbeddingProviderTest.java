package com.ai.fabric.examples.smoke;

import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SmokeEmbeddingProviderTest {

    private final SmokeEmbeddingProvider provider = new SmokeEmbeddingProvider();

    @Test
    void producesStableNormalizedEmbeddingsForSameText() {
        AIEmbeddingRequest request = AIEmbeddingRequest.builder()
            .text("stable customer support text")
            .build();

        AIEmbeddingResponse first = provider.generateEmbedding(request);
        AIEmbeddingResponse second = provider.generateEmbedding(request);

        assertThat(first.getModel()).isEqualTo("smoke");
        assertThat(first.getDimensions()).isEqualTo(SmokeEmbeddingProvider.DIMENSION);
        assertThat(first.getEmbedding()).hasSize(SmokeEmbeddingProvider.DIMENSION);
        assertThat(first.getEmbedding()).isEqualTo(second.getEmbedding());
        assertThat(l2Norm(first.getEmbedding())).isCloseTo(1.0, within(1.0e-12));
    }

    @Test
    void handlesBatchAndNullInputsWithoutExternalDependencies() {
        List<AIEmbeddingResponse> responses = provider.generateEmbeddings(List.of("alpha", "beta", ""));

        assertThat(responses).hasSize(3);
        assertThat(responses)
            .allSatisfy(response -> {
                assertThat(response.getModel()).isEqualTo("smoke");
                assertThat(response.getEmbedding()).hasSize(SmokeEmbeddingProvider.DIMENSION);
            });
        assertThat(provider.generateEmbeddings(null)).isEmpty();
        assertThat(provider.generateEmbedding(null).getEmbedding()).hasSize(SmokeEmbeddingProvider.DIMENSION);
    }

    @Test
    void exposesProviderMetadata() {
        Map<String, Object> status = provider.getStatus();

        assertThat(provider.getProviderName()).isEqualTo("smoke");
        assertThat(provider.isAvailable()).isTrue();
        assertThat(provider.getEmbeddingDimension()).isEqualTo(SmokeEmbeddingProvider.DIMENSION);
        assertThat(status)
            .containsEntry("provider", "smoke")
            .containsEntry("available", true)
            .containsEntry("dimension", SmokeEmbeddingProvider.DIMENSION)
            .containsEntry("details", "offline deterministic provider (smoke profile)");
    }

    private static double l2Norm(List<Double> vector) {
        return Math.sqrt(vector.stream()
            .mapToDouble(value -> value * value)
            .sum());
    }
}
