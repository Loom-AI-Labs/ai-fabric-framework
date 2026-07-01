package com.ai.fabric.realapps.providerlab.service;

import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.provider.AIProvider;
import ai.fabric.provider.ProviderConfig;
import ai.fabric.provider.ProviderStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderFailoverServiceTest {

    @Test
    void missingPrimaryFallsBackToSecondaryProvider() {
        ProviderFailoverService service = new ProviderFailoverService(List.of(
            provider("smoke", true, false)
        ));

        ProviderFailoverService.ProviderProbeResult result = service.runProbe(new ProviderFailoverService.ProviderProbeRequest(
            "secret prompt",
            "openai",
            List.of("smoke"),
            null
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.selectedProvider()).isEqualTo("smoke");
        assertThat(result.fallbackReason()).isEqualTo("PRIMARY_FAILED");
        assertThat(result.diagnostics()).containsEntry("rawPromptIncluded", false);
    }

    @Test
    void providerErrorsAreSafeAndTransientUrlIsNotPersisted() {
        ProviderFailoverService service = new ProviderFailoverService(List.of(
            provider("openai", true, true),
            provider("smoke", true, false)
        ));

        ProviderFailoverService.ProviderProbeResult result = service.runProbe(new ProviderFailoverService.ProviderProbeRequest(
            "do not expose me",
            "openai",
            List.of("smoke"),
            "https://example.invalid/file.pdf?token=secret"
        ));

        assertThat(result.selectedProvider()).isEqualTo("smoke");
        assertThat(result.attempts().getFirst().errorCode()).isEqualTo("PROVIDER_ERROR");
        assertThat(result.attempts().getFirst().message()).doesNotContain("do not expose me", "token=secret");
        assertThat(result.diagnostics())
            .containsEntry("transientFileSeen", true)
            .containsEntry("transientFileUrlPersisted", false);
    }

    private static AIProvider provider(String name, boolean available, boolean fail) {
        return new AIProvider() {
            @Override
            public String getProviderName() {
                return name;
            }

            @Override
            public boolean isAvailable() {
                return available;
            }

            @Override
            public AIGenerationResponse generateContent(AIGenerationRequest request) {
                if (fail) {
                    throw new IllegalStateException("boom " + request.getPrompt());
                }
                return AIGenerationResponse.builder()
                    .content("ok from " + name)
                    .model(name + "-model")
                    .tokensUsed(7)
                    .metadata(Map.of())
                    .generatedAt(LocalDateTime.now())
                    .status("OK")
                    .build();
            }

            @Override
            public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
                return AIEmbeddingResponse.builder()
                    .embedding(List.of(0.1, 0.2, 0.3))
                    .model(name + "-embedding")
                    .dimensions(3)
                    .processingTimeMs(0L)
                    .build();
            }

            @Override
            public ProviderStatus getStatus() {
                return ProviderStatus.builder().providerName(name).available(available).healthy(available).build();
            }

            @Override
            public ProviderConfig getConfig() {
                return ProviderConfig.builder().providerName(name).enabled(available).build();
            }
        };
    }
}
