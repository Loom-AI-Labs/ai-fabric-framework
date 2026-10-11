package ai.fabric.core;

import ai.fabric.cache.AICacheNames;
import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.embedding.EmbeddingProvider;
import ai.fabric.exception.AIServiceException;
import ai.fabric.provider.ProviderRequestOverrideSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AIEmbeddingServiceTest {

    private AIProviderConfig config;
    private EmbeddingProvider provider;
    private CacheManager cacheManager;
    private AIEmbeddingService service;

    @BeforeEach
    void setUp() {
        config = mock(AIProviderConfig.class);
        provider = mock(EmbeddingProvider.class);
        cacheManager = new ConcurrentMapCacheManager(AICacheNames.EMBEDDINGS);

        when(provider.getProviderName()).thenReturn("openai");
        when(provider.isAvailable()).thenReturn(true);
        when(config.resolveEmbeddingDefaults()).thenReturn(
            new AIProviderConfig.EmbeddingDefaults("openai", "text-embedding-3-small")
        );
        when(provider.generateEmbedding(any())).thenReturn(
            AIEmbeddingResponse.builder()
                .embedding(List.of(0.11, 0.22, 0.33))
                .model("text-embedding-3-small")
                .dimensions(3)
                .processingTimeMs(25L)
                .requestId(UUID.randomUUID().toString())
                .build()
        );

        service = new AIEmbeddingService(config, provider, cacheManager, null);
    }

    @Test
    void executeEmbeddingReusesCacheAcrossImplicitAndExplicitModelRequests() {
        AIEmbeddingRequest implicitModel = AIEmbeddingRequest.builder()
            .text("high performance gaming laptops")
            .build();
        AIEmbeddingRequest explicitModel = AIEmbeddingRequest.builder()
            .text("high performance gaming laptops")
            .model("text-embedding-3-small")
            .build();

        AIEmbeddingService.EmbeddingExecution first = service.executeEmbedding(implicitModel);
        AIEmbeddingService.EmbeddingExecution second = service.executeEmbedding(explicitModel);

        assertThat(first.cacheHit()).isFalse();
        assertThat(first.providerName()).isEqualTo("openai");
        assertThat(first.effectiveModel()).isEqualTo("text-embedding-3-small");
        assertThat(first.providerProcessingTimeMs()).isEqualTo(25L);
        assertThat(first.serviceProcessingTimeMs()).isNotNull();
        assertThat(first.serviceProcessingTimeMs()).isGreaterThanOrEqualTo(0L);

        assertThat(second.cacheHit()).isTrue();
        assertThat(second.providerName()).isEqualTo("openai");
        assertThat(second.effectiveModel()).isEqualTo("text-embedding-3-small");
        assertThat(second.providerProcessingTimeMs()).isNull();
        assertThat(second.serviceProcessingTimeMs()).isZero();

        verify(provider, times(1)).generateEmbedding(any());
    }

    @Test
    void executeEmbeddingSeparatesCacheByConnectionOverride() {
        AIEmbeddingRequest firstEndpoint = AIEmbeddingRequest.builder()
            .text("same text")
            .model("text-embedding-3-small")
            .parameters(connectionOverride("https://embeddings-a.example.com/v1", "key-a"))
            .build();
        AIEmbeddingRequest secondEndpoint = AIEmbeddingRequest.builder()
            .text("same text")
            .model("text-embedding-3-small")
            .parameters(connectionOverride("https://embeddings-b.example.com/v1", "key-b"))
            .build();

        AIEmbeddingService.EmbeddingExecution first = service.executeEmbedding(firstEndpoint);
        AIEmbeddingService.EmbeddingExecution second = service.executeEmbedding(secondEndpoint);

        assertThat(first.cacheHit()).isFalse();
        assertThat(second.cacheHit()).isFalse();
        verify(provider, times(2)).generateEmbedding(any());
    }

    @Test
    void preprocessTextBoundsTheNormalizedValue() {
        assertThat(service.preprocessText("value      ")).isEqualTo("value");
    }

    @Test
    void executeEmbeddingFailsWithoutExplicitCompatibleFallbackPolicy() {
        EmbeddingProvider fallback = fallbackProvider();
        when(provider.generateEmbedding(any())).thenThrow(new AIServiceException("primary unavailable"));
        when(config.getEnableFallback()).thenReturn(true);
        when(config.getEmbeddingFallbackProviders()).thenReturn(List.of("onnx"));
        when(config.getProviderDataPolicies()).thenReturn(Map.of(
            "openai", "external-processing",
            "onnx", "local-processing"
        ));
        service = new AIEmbeddingService(config, provider, cacheManager, fallback);

        assertThatThrownBy(() -> service.executeEmbedding(AIEmbeddingRequest.builder().text("policy").build()))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("primary unavailable");
        verify(fallback, never()).generateEmbedding(any());
    }

    @Test
    void executeEmbeddingRejectsDimensionCompatibleFallbackWithoutEmbeddingSpaceContract() {
        EmbeddingProvider fallback = fallbackProvider();
        when(provider.generateEmbedding(any())).thenThrow(new AIServiceException("primary unavailable"));
        when(config.getEnableFallback()).thenReturn(true);
        when(config.getEmbeddingFallbackProviders()).thenReturn(List.of("onnx"));
        when(config.getProviderDataPolicies()).thenReturn(Map.of(
            "openai", "approved-external",
            "onnx", "approved-external"
        ));
        when(config.getEmbeddingCompatibilityGroups()).thenReturn(Map.of(
            "openai", "openai-space",
            "onnx", "onnx-space"
        ));
        service = new AIEmbeddingService(config, provider, cacheManager, fallback);

        assertThatThrownBy(() -> service.executeEmbedding(AIEmbeddingRequest.builder().text("policy").build()))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("primary unavailable");
        verify(fallback, never()).generateEmbedding(any());
    }

    @Test
    void executeEmbeddingUsesNamedCompatibleFallbackAndReportsTransition() {
        EmbeddingProvider fallback = fallbackProvider();
        when(provider.generateEmbedding(any())).thenThrow(new AIServiceException("primary unavailable"));
        allowCompatibleFallback();
        service = new AIEmbeddingService(config, provider, cacheManager, fallback);

        AIEmbeddingService.EmbeddingExecution execution = service.executeEmbedding(
            AIEmbeddingRequest.builder().text("policy").build()
        );

        assertThat(execution.providerName()).isEqualTo("onnx");
        assertThat(execution.providerRecovery())
            .containsEntry("policy", "NAMED_COMPATIBLE_CHAIN")
            .containsEntry("originalProvider", "openai")
            .containsEntry("effectiveProvider", "onnx")
            .containsEntry("transitioned", true)
            .containsEntry("embeddingCompatibilityGroup", "customer-search-v1");
        assertThat(execution.response().getProvider()).isEqualTo("onnx");
        assertThat(execution.response().getMetadata())
            .containsEntry("providerRecovery", execution.providerRecovery());
    }

    @Test
    void batchEmbeddingsUseSameNamedCompatibleRecoveryContract() {
        EmbeddingProvider fallback = fallbackProvider();
        when(provider.generateEmbeddings(any())).thenThrow(new AIServiceException("primary unavailable"));
        when(fallback.generateEmbeddings(any())).thenReturn(List.of(
            embedding("onnx-local"),
            embedding("onnx-local")
        ));
        allowCompatibleFallback();
        service = new AIEmbeddingService(config, provider, cacheManager, fallback);

        List<AIEmbeddingResponse> responses = service.generateEmbeddings(
            List.of("first", "second"),
            "document"
        );

        assertThat(responses).hasSize(2).allSatisfy(response -> {
            assertThat(response.getProvider()).isEqualTo("onnx");
            assertThat(response.getMetadata()).containsKey("providerRecovery");
            @SuppressWarnings("unchecked")
            Map<String, Object> recovery = (Map<String, Object>) response.getMetadata().get("providerRecovery");
            assertThat(recovery)
                .containsEntry("policy", "NAMED_COMPATIBLE_CHAIN")
                .containsEntry("transitioned", true);
        });
    }

    @Test
    void executeEmbeddingUsesConfiguredFallbackOrderAcrossRegisteredProviders() {
        EmbeddingProvider firstFallback = mock(EmbeddingProvider.class);
        when(firstFallback.getProviderName()).thenReturn("azure");
        when(firstFallback.isAvailable()).thenReturn(true);
        when(firstFallback.generateEmbedding(any())).thenThrow(new AIServiceException("azure unavailable"));
        EmbeddingProvider secondFallback = fallbackProvider();
        when(provider.generateEmbedding(any())).thenThrow(new AIServiceException("primary unavailable"));
        when(config.getEnableFallback()).thenReturn(true);
        when(config.getEmbeddingFallbackProviders()).thenReturn(List.of("azure", "onnx"));
        when(config.getProviderDataPolicies()).thenReturn(Map.of(
            "openai", "approved-external",
            "azure", "approved-external",
            "onnx", "approved-external"
        ));
        when(config.getEmbeddingCompatibilityGroups()).thenReturn(Map.of(
            "openai", "customer-search-v1",
            "azure", "customer-search-v1",
            "onnx", "customer-search-v1"
        ));
        service = new AIEmbeddingService(
            config,
            provider,
            cacheManager,
            null,
            List.of(provider, firstFallback, secondFallback)
        );

        AIEmbeddingService.EmbeddingExecution execution = service.executeEmbedding(
            AIEmbeddingRequest.builder().text("policy").build()
        );

        verify(firstFallback).generateEmbedding(any());
        verify(secondFallback).generateEmbedding(any());
        assertThat(execution.providerName()).isEqualTo("onnx");
        assertThat(execution.providerRecovery().get("attempts").toString())
            .contains("openai", "FAILED", "azure", "onnx", "SUCCEEDED");
    }

    private void allowCompatibleFallback() {
        when(config.getEnableFallback()).thenReturn(true);
        when(config.getEmbeddingFallbackProviders()).thenReturn(List.of("onnx"));
        when(config.getProviderDataPolicies()).thenReturn(Map.of(
            "openai", "approved-external",
            "onnx", "approved-external"
        ));
        when(config.getEmbeddingCompatibilityGroups()).thenReturn(Map.of(
            "openai", "customer-search-v1",
            "onnx", "customer-search-v1"
        ));
    }

    private EmbeddingProvider fallbackProvider() {
        EmbeddingProvider fallback = mock(EmbeddingProvider.class);
        when(fallback.getProviderName()).thenReturn("onnx");
        when(fallback.isAvailable()).thenReturn(true);
        when(fallback.generateEmbedding(any())).thenReturn(embedding("onnx-local"));
        return fallback;
    }

    private AIEmbeddingResponse embedding(String model) {
        return AIEmbeddingResponse.builder()
            .embedding(List.of(0.4, 0.5, 0.6))
            .model(model)
            .dimensions(3)
            .processingTimeMs(4L)
            .requestId(UUID.randomUUID().toString())
            .build();
    }

    private static Map<String, Object> connectionOverride(String baseUrl, String apiKey) {
        return Map.of(
            ProviderRequestOverrideSupport.PARAM_PROVIDER_CONNECTION_OVERRIDE,
            Map.of(
                ProviderRequestOverrideSupport.KEY_BASE_URL, baseUrl,
                ProviderRequestOverrideSupport.KEY_API_KEY, apiKey
            )
        );
    }
}
