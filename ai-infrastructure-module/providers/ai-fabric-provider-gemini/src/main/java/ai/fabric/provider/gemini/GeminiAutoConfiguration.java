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

    static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    static final String DEFAULT_MODEL = "gemini-2.5-flash";
    static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-004";
    static final int DEFAULT_MAX_TOKENS = 2_000;
    static final double DEFAULT_TEMPERATURE = 0.3d;
    static final int DEFAULT_TIMEOUT_SECONDS = 30;

    @Bean(name = "geminiProviderConfig")
    @ConditionalOnMissingBean(name = "geminiProviderConfig")
    @ConditionalOnProperty(prefix = "ai.providers.gemini", name = "enabled", havingValue = "true")
    public ProviderConfig geminiProviderConfig(AIProviderConfig aiProviderConfig) {
        AIProviderConfig.GeminiConfig gemini = aiProviderConfig.getGemini();
        boolean hasApiKey = hasText(gemini.getApiKey());

        return ProviderConfig.builder()
            .providerName("gemini")
            .apiKey(gemini.getApiKey())
            .baseUrl(normalizeBaseUrl(gemini.getBaseUrl()))
            .defaultModel(hasText(gemini.getModel()) ? gemini.getModel() : DEFAULT_MODEL)
            .defaultEmbeddingModel(hasText(gemini.getEmbeddingModel()) ? gemini.getEmbeddingModel() : DEFAULT_EMBEDDING_MODEL)
            .embeddingApiKey(hasText(aiProviderConfig.getEmbeddingApiKey())
                ? aiProviderConfig.getEmbeddingApiKey()
                : gemini.getApiKey())
            .embeddingBaseUrl(normalizeBaseUrl(hasText(aiProviderConfig.getEmbeddingBaseUrl())
                ? aiProviderConfig.getEmbeddingBaseUrl()
                : gemini.getBaseUrl()))
            .maxTokens(gemini.getMaxTokens() != null ? gemini.getMaxTokens() : DEFAULT_MAX_TOKENS)
            .temperature(gemini.getTemperature() != null ? gemini.getTemperature() : DEFAULT_TEMPERATURE)
            .timeoutSeconds(gemini.getTimeout() != null ? gemini.getTimeout() : DEFAULT_TIMEOUT_SECONDS)
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
