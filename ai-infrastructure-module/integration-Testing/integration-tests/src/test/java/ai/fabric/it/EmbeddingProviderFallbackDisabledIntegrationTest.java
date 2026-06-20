package ai.fabric.it;

import ai.fabric.cache.AICacheConfig;
import ai.fabric.cache.AICacheNames;
import ai.fabric.config.AIProviderConfig;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.embedding.EmbeddingProvider;
import ai.fabric.exception.AIServiceException;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(classes = TestApplication.class,
    properties = {
        "ai.providers.embedding-provider=openai",
        "ai.providers.enable-fallback=false",
        "ai.providers.openai.enabled=true",
        "ai.providers.openai.api-key=sk-test-key",
        "ai.providers.openai.base-url=https://api.openai.com/v1",
        "ai.providers.openai.model=gpt-4o-mini",
        "ai.providers.openai.embedding-model=text-embedding-3-small"
    })
@ActiveProfiles("dev")
@Import(AICacheConfig.class)
class EmbeddingProviderFallbackDisabledIntegrationTest {

    @Autowired
    private AIProviderConfig providerConfig;

    @Autowired
    private CacheManager cacheManager;

    @MockitoBean(name = "openAiSpringAiEmbeddingProvider")
    private EmbeddingProvider primaryEmbeddingProvider;

    @MockitoBean(name = "onnxFallbackEmbeddingProvider")
    private EmbeddingProvider fallbackEmbeddingProvider;

    private Cache cache;
    private AIEmbeddingService testEmbeddingService;

    @BeforeEach
    void setUp() {
        cache = cacheManager.getCache(AICacheNames.EMBEDDINGS);
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
    @DisplayName("Fallback pathway is skipped when disabled in configuration")
    void fallbackDisabledSkipsFallbackProvider() {
        Mockito.when(primaryEmbeddingProvider.generateEmbedding(ArgumentMatchers.any()))
            .thenThrow(new AIServiceException("Simulated primary outage"));

        AIEmbeddingRequest request = AIEmbeddingRequest.builder()
            .text("disable fallback scenario")
            .model("text-embedding-3-small")
            .build();

        assertThrows(AIServiceException.class, () -> testEmbeddingService.generateEmbedding(request));

        verify(primaryEmbeddingProvider).generateEmbedding(ArgumentMatchers.any());
        verify(fallbackEmbeddingProvider, never()).generateEmbedding(ArgumentMatchers.any());
    }
}
