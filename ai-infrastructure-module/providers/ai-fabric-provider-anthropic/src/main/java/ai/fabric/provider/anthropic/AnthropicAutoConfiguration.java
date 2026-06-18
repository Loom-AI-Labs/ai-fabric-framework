package ai.fabric.provider.anthropic;

import ai.fabric.config.AIInfrastructureAutoConfiguration;
import ai.fabric.config.AIProviderConfig;
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
 * Auto-configuration for the Anthropic provider module.
 */
@Slf4j
@AutoConfiguration
@AutoConfigureAfter(AIInfrastructureAutoConfiguration.class)
@ConditionalOnClass(AnthropicProvider.class)
public class AnthropicAutoConfiguration {

    static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1";
    static final String DEFAULT_MODEL = "claude-3-7-sonnet-latest";
    static final int DEFAULT_MAX_TOKENS = 2_000;
    static final double DEFAULT_TEMPERATURE = 0.3d;
    static final int DEFAULT_TIMEOUT_SECONDS = 30;

    @Bean(name = "anthropicProviderConfig")
    @ConditionalOnMissingBean(name = "anthropicProviderConfig")
    @ConditionalOnProperty(prefix = "ai.providers.anthropic", name = "enabled", havingValue = "true")
    public ProviderConfig anthropicProviderConfig(AIProviderConfig aiProviderConfig) {
        AIProviderConfig.AnthropicConfig anthropic = aiProviderConfig.getAnthropic();
        boolean hasApiKey = hasText(anthropic.getApiKey());

        return ProviderConfig.builder()
            .providerName("anthropic")
            .apiKey(anthropic.getApiKey())
            .baseUrl(normalizeBaseUrl(anthropic.getBaseUrl()))
            .defaultModel(hasText(anthropic.getModel()) ? anthropic.getModel() : DEFAULT_MODEL)
            .defaultEmbeddingModel(null)
            .maxTokens(anthropic.getMaxTokens() != null ? anthropic.getMaxTokens() : DEFAULT_MAX_TOKENS)
            .temperature(anthropic.getTemperature() != null ? anthropic.getTemperature() : DEFAULT_TEMPERATURE)
            .timeoutSeconds(anthropic.getTimeout() != null ? anthropic.getTimeout() : DEFAULT_TIMEOUT_SECONDS)
            .maxRetries(3)
            .retryDelayMs(1000L)
            .rateLimitPerMinute(60)
            .rateLimitPerDay(10_000)
            .enabled(anthropic.isEnabled() && hasApiKey)
            .priority(anthropic.getPriority())
            .build();
    }

    @Bean
    @ConditionalOnBean(name = "anthropicProviderConfig")
    public AnthropicProvider anthropicProvider(@Qualifier("anthropicProviderConfig") ProviderConfig providerConfig,
                                               AIHttpClientFactory httpClientFactory) {
        HttpClient httpClient = httpClientFactory.create(
            Duration.ofSeconds(5),
            providerConfig.getTimeoutSeconds() != null ? Duration.ofSeconds(providerConfig.getTimeoutSeconds()) : null
        );
        return new AnthropicProvider(providerConfig, httpClient);
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
