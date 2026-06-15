package ai.fabric.provider.gemini;

import ai.fabric.config.AIInfrastructureAutoConfiguration;
import ai.fabric.config.AIProviderConfig;
import ai.fabric.embedding.EmbeddingProvider;
import ai.fabric.http.AIHttpClientFactory;
import ai.fabric.http.HttpClient;
import ai.fabric.provider.ProviderConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * Auto-configuration for the Google Gemini provider module.
 */
@Slf4j
@AutoConfiguration
@AutoConfigureAfter(AIInfrastructureAutoConfiguration.class)
@ConditionalOnClass(GeminiProvider.class)
public class GeminiAutoConfiguration {

    @Bean(name = "geminiProviderConfig")
    @ConditionalOnMissingBean(name = "geminiProviderConfig")
    @ConditionalOnProperty(prefix = "ai.providers.gemini", name = "enabled", havingValue = "true")
    public ProviderConfig geminiProviderConfig(AIProviderConfig aiProviderConfig) {
        AIProviderConfig.GeminiConfig gemini = aiProviderConfig.getGemini();
        boolean hasApiKey = gemini.getApiKey() != null && !gemini.getApiKey().isBlank();

        return ProviderConfig.builder()
            .providerName("gemini")
            .apiKey(gemini.getApiKey())
            .baseUrl(gemini.getBaseUrl())
            .defaultModel(gemini.getModel())
            .defaultEmbeddingModel(gemini.getEmbeddingModel())
            .maxTokens(gemini.getMaxTokens())
            .temperature(gemini.getTemperature())
            .timeoutSeconds(gemini.getTimeout())
            .maxRetries(3)
            .retryDelayMs(1000L)
            .rateLimitPerMinute(60)
            .rateLimitPerDay(10_000)
            .enabled(gemini.isEnabled() && hasApiKey)
            .priority(gemini.getPriority())
            .build();
    }

    @Bean
    @ConditionalOnBean(name = "geminiProviderConfig")
    public GeminiProvider geminiProvider(@Qualifier("geminiProviderConfig") ProviderConfig providerConfig,
                                       AIHttpClientFactory httpClientFactory) {
        HttpClient httpClient = httpClientFactory.create(
            Duration.ofSeconds(5),
            providerConfig.getTimeoutSeconds() != null ? Duration.ofSeconds(providerConfig.getTimeoutSeconds()) : null
        );
        return new GeminiProvider(providerConfig, httpClient);
    }

    @Bean
    @ConditionalOnBean(name = "geminiProviderConfig")
    @ConditionalOnProperty(name = "ai.providers.embedding-provider", havingValue = "gemini")
    public EmbeddingProvider geminiEmbeddingProvider(AIProviderConfig aiProviderConfig,
                                                     AIHttpClientFactory httpClientFactory) {
        HttpClient httpClient = httpClientFactory.create();
        return new GeminiEmbeddingProvider(aiProviderConfig, httpClient);
    }
}
