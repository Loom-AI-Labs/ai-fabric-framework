package ai.fabric.provider.openai;

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
 * Auto-configuration for the OpenAI provider module.
 */
@Slf4j
@AutoConfiguration
@AutoConfigureAfter(AIInfrastructureAutoConfiguration.class)
@ConditionalOnClass(OpenAIProvider.class)
public class OpenAIAutoConfiguration {

    @Bean(name = "openAIProviderConfig")
    @ConditionalOnMissingBean(name = "openAIProviderConfig")
    @ConditionalOnProperty(prefix = "ai.providers.openai", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ProviderConfig openAIProviderConfig(AIProviderConfig aiProviderConfig) {
        AIProviderConfig.OpenAIConfig openai = aiProviderConfig.getOpenai();
        boolean hasApiKey = openai.getApiKey() != null && !openai.getApiKey().isBlank();

        return ProviderConfig.builder()
            .providerName("openai")
            .apiKey(openai.getApiKey())
            .baseUrl(openai.getBaseUrl())
            .defaultModel(openai.getModel())
            .defaultEmbeddingModel(openai.getEmbeddingModel())
            .embeddingApiKey(openai.getEmbeddingApiKey())
            .embeddingBaseUrl(openai.getEmbeddingBaseUrl())
            .maxTokens(openai.getMaxTokens())
            .temperature(openai.getTemperature())
            .timeoutSeconds(openai.getTimeout())
            .maxRetries(3)
            .retryDelayMs(1000L)
            .rateLimitPerMinute(60)
            .rateLimitPerDay(10_000)
            .enabled(openai.isEnabled() && hasApiKey)
            .priority(openai.getPriority())
            .build();
    }

    @Bean
    @ConditionalOnBean(name = "openAIProviderConfig")
    public OpenAIProvider openAIProvider(@Qualifier("openAIProviderConfig") ProviderConfig providerConfig,
                                         AIHttpClientFactory httpClientFactory) {
        HttpClient httpClient = httpClientFactory.create(
            Duration.ofSeconds(5),
            providerConfig.getTimeoutSeconds() != null ? Duration.ofSeconds(providerConfig.getTimeoutSeconds()) : null
        );
        return new OpenAIProvider(providerConfig, httpClient);
    }

    @Bean
    @ConditionalOnBean(name = "openAIProviderConfig")
    @ConditionalOnProperty(name = "ai.providers.embedding-provider", havingValue = "openai")
    public EmbeddingProvider openAIEmbeddingProvider(AIProviderConfig aiProviderConfig,
                                                     AIHttpClientFactory httpClientFactory) {
        HttpClient httpClient = httpClientFactory.create();
        return new OpenAIEmbeddingProvider(aiProviderConfig, httpClient);
    }
}
