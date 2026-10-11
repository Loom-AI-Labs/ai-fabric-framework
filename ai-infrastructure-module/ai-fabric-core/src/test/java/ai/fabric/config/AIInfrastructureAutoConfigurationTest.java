package ai.fabric.config;

import ai.fabric.embedding.EmbeddingProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AIInfrastructureAutoConfigurationTest {

    @Test
    void rejectsUnavailableConfiguredEmbeddingProviderInsteadOfSelectingAnotherProvider() {
        AIProviderConfig providerConfig = new AIProviderConfig();
        providerConfig.setEmbeddingProvider("onnx");

        EmbeddingProvider openai = mock(EmbeddingProvider.class);
        when(openai.getProviderName()).thenReturn("openai");

        @SuppressWarnings("unchecked")
        ObjectProvider<CacheManager> cacheManagerProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<EmbeddingProvider> fallbackProvider = mock(ObjectProvider.class);

        AIInfrastructureAutoConfiguration configuration = new AIInfrastructureAutoConfiguration();

        assertThatThrownBy(() -> configuration.aiEmbeddingService(
            providerConfig,
            List.of(openai),
            cacheManagerProvider,
            fallbackProvider
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Configured embedding provider is unavailable: onnx")
            .hasMessageContaining("Available providers: openai");
    }
}
