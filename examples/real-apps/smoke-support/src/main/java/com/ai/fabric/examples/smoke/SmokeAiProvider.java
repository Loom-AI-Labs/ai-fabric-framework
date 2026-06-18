package com.ai.fabric.examples.smoke;

import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.provider.AIProvider;
import ai.fabric.provider.ProviderConfig;
import ai.fabric.provider.ProviderStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Offline deterministic {@link AIProvider} used by the "smoke" profile.
 *
 * <p>Its only purpose is to satisfy the framework's provider wiring so an example app can BOOT with no
 * API keys or network access. Generation returns a deterministic local response rather than calling an
 * external model, so endpoints that call the LLM remain testable under this profile.</p>
 *
 * <p>Selected via {@code ai.providers.llm-provider: smoke} (see {@code application-smoke.yml}).</p>
 */
public class SmokeAiProvider implements AIProvider {

    static final String NAME = "smoke";

    @Override
    public String getProviderName() {
        return NAME;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public AIGenerationResponse generateContent(AIGenerationRequest request) {
        return AIGenerationResponse.builder()
            .id("smoke-" + UUID.randomUUID())
            .requestId(request != null ? request.getEntityId() : null)
            .content("[smoke profile] deterministic local response - no external model was called")
            .model(NAME)
            .tokensUsed(0)
            .confidence(0.0)
            .processingTimeMs(0L)
            .generatedAt(LocalDateTime.now())
            .status("OK")
            .build();
    }

    @Override
    public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
        String text = request != null ? request.getText() : "";
        return SmokeEmbeddingProvider.deterministicEmbedding(text, NAME);
    }

    @Override
    public ProviderStatus getStatus() {
        return ProviderStatus.builder()
            .providerName(NAME)
            .available(true)
            .healthy(true)
            .successRate(1.0)
            .averageResponseTime(0.0)
            .lastUpdated(LocalDateTime.now())
            .details("offline deterministic provider (smoke profile)")
            .build();
    }

    @Override
    public ProviderConfig getConfig() {
        return ProviderConfig.builder()
            .providerName(NAME)
            .enabled(true)
            .apiKey("smoke-local-key")
            .baseUrl("smoke://local")
            .defaultModel(NAME)
            .timeoutSeconds(1)
            .maxRetries(0)
            .build();
    }
}
