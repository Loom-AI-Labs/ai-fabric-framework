package ai.fabric.core;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.embedding.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
        cacheManager = new ConcurrentMapCacheManager("embeddings");

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
}
