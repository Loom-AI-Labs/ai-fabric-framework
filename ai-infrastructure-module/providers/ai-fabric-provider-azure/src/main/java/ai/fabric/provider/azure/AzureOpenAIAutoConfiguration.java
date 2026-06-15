package ai.fabric.provider.azure;

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
 * Auto-configuration for the Azure OpenAI provider module.
 */
@Slf4j
@AutoConfiguration
@AutoConfigureAfter(AIInfrastructureAutoConfiguration.class)
@ConditionalOnClass(AzureOpenAIProvider.class)
public class AzureOpenAIAutoConfiguration {

    @Bean(name = "azureProviderConfig")
    @ConditionalOnMissingBean(name = "azureProviderConfig")
    @ConditionalOnProperty(prefix = "ai.providers.azure", name = "enabled", havingValue = "true")
    public ProviderConfig azureProviderConfig(AIProviderConfig providerConfig) {
        AIProviderConfig.AzureConfig azure = providerConfig.getAzure();
        boolean hasCredentials = azure.getApiKey() != null && !azure.getApiKey().isBlank();
        boolean hasEndpoint = azure.getEndpoint() != null && !azure.getEndpoint().isBlank();

        return ProviderConfig.builder()
            .providerName("azure")
            .apiKey(azure.getApiKey())
            .baseUrl(normalizeEndpoint(azure.getEndpoint()))
            .defaultModel(azure.getDeploymentName())
            .defaultEmbeddingModel(azure.getEmbeddingDeploymentName())
            .maxTokens(2000)
            .temperature(0.3)
            .timeoutSeconds(azure.getTimeout())
            .maxRetries(3)
            .retryDelayMs(1500L)
            .rateLimitPerMinute(60)
            .rateLimitPerDay(20_000)
            .enabled(azure.isEnabled() && hasCredentials && hasEndpoint)
            .priority(azure.getPriority())
            .build();
    }

    @Bean
    @ConditionalOnBean(name = "azureProviderConfig")
    public AzureOpenAIProvider azureOpenAIProvider(@Qualifier("azureProviderConfig") ProviderConfig providerConfig,
                                                   AIProviderConfig aiProviderConfig,
                                                   AIHttpClientFactory httpClientFactory) {
        Duration readTimeout = providerConfig.getTimeoutSeconds() != null
            ? Duration.ofSeconds(providerConfig.getTimeoutSeconds())
            : null;
        HttpClient httpClient = httpClientFactory.create(Duration.ofSeconds(5), readTimeout);
        return new AzureOpenAIProvider(providerConfig, aiProviderConfig.getAzure(), httpClient);
    }

    @Bean
    @ConditionalOnBean(name = "azureProviderConfig")
    @ConditionalOnProperty(name = "ai.providers.embedding-provider", havingValue = "azure")
    public EmbeddingProvider azureEmbeddingProvider(AIProviderConfig providerConfig,
                                                    AIHttpClientFactory httpClientFactory) {
        HttpClient httpClient = httpClientFactory.create();
        return new AzureOpenAIEmbeddingProvider(providerConfig, httpClient);
    }

    private String normalizeEndpoint(String endpoint) {
        if (endpoint == null) {
            return null;
        }
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }
}
