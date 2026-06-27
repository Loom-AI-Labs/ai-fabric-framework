package ai.fabric.it;

import ai.fabric.cache.AICacheConfig;
import ai.fabric.cache.AICacheNames;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.embedding.EmbeddingProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("dev")
@Import(AICacheConfig.class)
class EmbeddingCachingIntegrationTest {

    @Autowired
    private AIEmbeddingService embeddingService;

    @Autowired
    private CacheManager cacheManager;

    @MockitoBean
    private EmbeddingProvider embeddingProvider;

    private Cache cache;

    @BeforeEach
    void setUp() {
        cache = cacheManager.getCache(AICacheNames.EMBEDDINGS);
        if (cache != null) {
            cache.clear();
        }
        embeddingService.clearMetrics();

        Mockito.reset(embeddingProvider);
        Mockito.when(embeddingProvider.getProviderName()).thenReturn("mock");
        Mockito.when(embeddingProvider.isAvailable()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.clear();
        }
    }

    @Test
    @DisplayName("Embedding cache records miss then hit and reuses cached response")
    void embeddingCacheStoresAndReusesResponses() {
        AtomicInteger invocationCounter = new AtomicInteger();

        Mockito.when(embeddingProvider.generateEmbedding(ArgumentMatchers.any()))
            .thenAnswer(invocation -> {
                invocationCounter.incrementAndGet();
                return AIEmbeddingResponse.builder()
                    .embedding(List.of(0.42, 0.17, 0.99))
                    .model("mock-model")
                    .dimensions(3)
                    .processingTimeMs(5L)
                    .requestId(UUID.randomUUID().toString())
                    .build();
            });

        AIEmbeddingRequest request = AIEmbeddingRequest.builder()
            .text("luxury swiss timepiece")
            .model("mock-model")
            .build();

        AIEmbeddingResponse firstResponse = embeddingService.generateEmbedding(request);
        assertEquals(1, invocationCounter.get(), "Embedding provider should be invoked on cache miss");

        AIEmbeddingResponse cachedResponse = embeddingService.generateEmbedding(request);
        assertEquals(1, invocationCounter.get(), "Second invocation should be served from cache");
        assertSame(firstResponse, cachedResponse, "Cached response should be the same instance as original response");

        assertNotNull(cache, "Embeddings cache should be available");
        String cacheKey = request.getText() + "_" + request.getModel() + "_mock_";
        assertNotNull(cache.get(cacheKey), "Cache should contain stored embedding for subsequent hits");

        Map<String, Object> metrics = embeddingService.getPerformanceMetrics();
        Map<?, ?> hits = (Map<?, ?>) metrics.get("cacheHits");
        Map<?, ?> misses = (Map<?, ?>) metrics.get("cacheMisses");
        assertEquals(1L, hits.get("mock"), "Cache hits should be tracked for provider");
        assertEquals(1L, misses.get("mock"), "Cache misses should be tracked for provider");

        Mockito.verify(embeddingProvider, Mockito.times(1)).generateEmbedding(ArgumentMatchers.any());
    }
}
