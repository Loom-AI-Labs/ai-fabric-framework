package ai.fabric.provider.cohere;

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
 * Auto-configuration for the Cohere provider module.
 */
@Slf4j
@AutoConfiguration
@AutoConfigureAfter(AIInfrastructureAutoConfiguration.class)
@ConditionalOnClass(CohereProvider.class)
public class CohereAutoConfiguration {

    static final String DEFAULT_BASE_URL = "https://api.cohere.ai/v1";
    static final String DEFAULT_CHAT_MODEL = "command-r7b-12-2024";
    static final String DEFAULT_EMBEDDING_MODEL = "embed-english-v3.0";
    static final int DEFAULT_MAX_TOKENS = 2_000;
    static final double DEFAULT_TEMPERATURE = 0.3d;
    static final int DEFAULT_TIMEOUT_SECONDS = 30;

    @Bean(name = "cohereProviderConfig")
    @ConditionalOnMissingBean(name = "cohereProviderConfig")
    @ConditionalOnProperty(prefix = "ai.providers.cohere", name = "enabled", havingValue = "true")
    public ProviderConfig cohereProviderConfig(AIProviderConfig aiProviderConfig) {
        AIProviderConfig.CohereConfig cohere = aiProviderConfig.getCohere();
        boolean hasApiKey = hasText(cohere.getApiKey());

        return ProviderConfig.builder()
            .providerName("cohere")
            .apiKey(cohere.getApiKey())
            .baseUrl(normalizeBaseUrl(cohere.getBaseUrl()))
            .defaultModel(hasText(cohere.getModel()) ? cohere.getModel() : DEFAULT_CHAT_MODEL)
            .defaultEmbeddingModel(hasText(cohere.getEmbeddingModel()) ? cohere.getEmbeddingModel() : DEFAULT_EMBEDDING_MODEL)
            .maxTokens(cohere.getMaxTokens() != null ? cohere.getMaxTokens() : DEFAULT_MAX_TOKENS)
            .temperature(cohere.getTemperature() != null ? cohere.getTemperature() : DEFAULT_TEMPERATURE)
            .timeoutSeconds(cohere.getTimeout() != null ? cohere.getTimeout() : DEFAULT_TIMEOUT_SECONDS)
            .maxRetries(3)
            .retryDelayMs(1000L)
            .rateLimitPerMinute(60)
            .rateLimitPerDay(10_000)
            .enabled(cohere.isEnabled() && hasApiKey)
            .priority(cohere.getPriority())
            .build();
    }

    @Bean
    @ConditionalOnBean(name = "cohereProviderConfig")
    public CohereProvider cohereProvider(@Qualifier("cohereProviderConfig") ProviderConfig providerConfig,
                                         AIHttpClientFactory httpClientFactory) {
        HttpClient httpClient = httpClientFactory.create(
            Duration.ofSeconds(5),
            providerConfig.getTimeoutSeconds() != null ? Duration.ofSeconds(providerConfig.getTimeoutSeconds()) : null
        );
        return new CohereProvider(providerConfig, httpClient);
    }

    @Bean
    @ConditionalOnBean(name = "cohereProviderConfig")
    @ConditionalOnProperty(name = "ai.providers.embedding-provider", havingValue = "cohere")
    public EmbeddingProvider cohereEmbeddingProvider(AIProviderConfig aiProviderConfig,
                                                     AIHttpClientFactory httpClientFactory) {
        HttpClient httpClient = httpClientFactory.create();
        return new CohereEmbeddingProvider(aiProviderConfig, httpClient);
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (!hasText(baseUrl)) {
            return DEFAULT_BASE_URL;
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
