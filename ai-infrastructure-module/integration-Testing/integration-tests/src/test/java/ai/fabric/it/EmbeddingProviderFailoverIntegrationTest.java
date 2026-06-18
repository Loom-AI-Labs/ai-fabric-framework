package ai.fabric.it;

import ai.fabric.cache.AICacheConfig;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.embedding.EmbeddingProvider;
import ai.fabric.exception.AIServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@SpringBootTest(classes = TestApplication.class,
    properties = {
        "ai.providers.embedding-provider=openai",
        "ai.providers.enable-fallback=true",
        "ai.providers.openai.enabled=true",
        "ai.providers.openai.api-key=sk-test-key",
        "ai.providers.openai.base-url=https://api.openai.com/v1",
        "ai.providers.openai.model=gpt-4o-mini",
        "ai.providers.openai.embedding-model=text-embedding-3-small"
    })
@ActiveProfiles("dev")
@Import(AICacheConfig.class)
class EmbeddingProviderFailoverIntegrationTest {

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private AIProviderConfig providerConfig;

    @MockBean(name = "openaiEmbeddingProvider")
    private EmbeddingProvider primaryEmbeddingProvider;

    @MockBean(name = "onnxFallbackEmbeddingProvider")
    private EmbeddingProvider fallbackEmbeddingProvider;

    private Cache cache;
    private AIEmbeddingService testEmbeddingService;

    @BeforeEach
    void setUp() {
        cache = cacheManager.getCache("embeddings");
        if (cache != null) {
            cache.clear();
        }

        Mockito.reset(primaryEmbeddingProvider, fallbackEmbeddingProvider);
        Mockito.when(primaryEmbeddingProvider.getProviderName()).thenReturn("openai");
        Mockito.when(primaryEmbeddingProvider.isAvailable()).thenReturn(true);

        Mockito.when(fallbackEmbeddingProvider.getProviderName()).thenReturn("onnx");
        Mockito.when(fallbackEmbeddingProvider.isAvailable()).thenReturn(true);

        testEmbeddingService = new AIEmbeddingService(providerConfig, primaryEmbeddingProvider, cacheManager, fallbackEmbeddingProvider);
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.clear();
        }
    }

    @Test
    @DisplayName("Embedding service falls back to ONNX when primary fails and recovers when primary is available")
    void embeddingServiceFallsBackAndRecovers() {
        AtomicBoolean primaryShouldFail = new AtomicBoolean(true);
        AtomicReference<AIEmbeddingResponse> primarySuccessResponse = new AtomicReference<>();

        Mockito.when(primaryEmbeddingProvider.generateEmbedding(ArgumentMatchers.any()))
            .thenAnswer(invocation -> {
                if (primaryShouldFail.get()) {
                    throw new AIServiceException("Simulated OpenAI outage");
                }
                return primarySuccessResponse.get();
            });

        AIEmbeddingResponse fallbackResponse = AIEmbeddingResponse.builder()
            .embedding(List.of(0.11, 0.22, 0.33))
            .model("onnx-fallback")
            .dimensions(3)
            .processingTimeMs(7L)
            .requestId(UUID.randomUUID().toString())
            .build();

        Mockito.when(fallbackEmbeddingProvider.generateEmbedding(ArgumentMatchers.any()))
            .thenReturn(fallbackResponse);

        AIEmbeddingRequest request = AIEmbeddingRequest.builder()
            .text("simulate outage for embedding")
            .model("text-embedding-3-small")
            .build();

        AIEmbeddingResponse firstResponse = testEmbeddingService.generateEmbedding(request);
        assertNotNull(firstResponse.getEmbedding(), "Fallback provider should supply embedding vector during outage");
        assertEquals(fallbackResponse.getEmbedding().size(), firstResponse.getEmbedding().size(),
            "Fallback provider should return an embedding of expected dimensions");
        assertEquals(fallbackResponse.getModel(), firstResponse.getModel(),
            "Fallback response model should propagate to caller");
        assertEquals(fallbackResponse.getDimensions(), firstResponse.getDimensions(),
            "Fallback response metadata should propagate to caller");

        assertEquals(1, Mockito.mockingDetails(primaryEmbeddingProvider).getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getName().equals("generateEmbedding"))
            .count(), "Primary provider should be attempted once during outage");
        Mockito.verify(fallbackEmbeddingProvider, Mockito.times(1)).generateEmbedding(ArgumentMatchers.any());

        Map<String, Object> metricsAfterFallback = testEmbeddingService.getPerformanceMetrics();
        Map<?, ?> misses = (Map<?, ?>) metricsAfterFallback.get("cacheMisses");
        assertEquals(1L, misses.get("onnx"), "Fallback provider miss should be recorded");

        if (cache != null) {
            cache.clear();
        }

        primaryShouldFail.set(false);
        AIEmbeddingResponse recoveryResponse = AIEmbeddingResponse.builder()
            .embedding(List.of(0.9, 0.8, 0.7))
            .model("text-embedding-3-small")
            .dimensions(3)
            .processingTimeMs(6L)
            .requestId(UUID.randomUUID().toString())
            .build();
        primarySuccessResponse.set(recoveryResponse);

        AIEmbeddingResponse secondResponse = testEmbeddingService.generateEmbedding(request);
        assertEquals(recoveryResponse.getEmbedding(), secondResponse.getEmbedding(),
            "Service should resume using primary provider once available");
        assertEquals(recoveryResponse.getModel(), secondResponse.getModel(),
            "Primary provider metadata should be reflected after recovery");

        assertEquals(2, Mockito.mockingDetails(primaryEmbeddingProvider).getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getName().equals("generateEmbedding"))
            .count(), "Primary provider should be retried after recovery");
        Mockito.verify(fallbackEmbeddingProvider, Mockito.times(1)).generateEmbedding(ArgumentMatchers.any());

        Map<String, Object> metrics = testEmbeddingService.getPerformanceMetrics();
        Map<?, ?> hits = (Map<?, ?>) metrics.get("cacheHits");
        assertNotNull(hits, "Cache metrics should be available after recovery");
    }

}
