package com.ai.infrastructure.provider.onnx;

import com.ai.infrastructure.config.AIInfrastructureAutoConfiguration;
import com.ai.infrastructure.config.AIProviderConfig;
import com.ai.infrastructure.embedding.EmbeddingProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Auto-configuration for ONNX embedding provider support.
 */
@Slf4j
@AutoConfiguration
@AutoConfigureAfter(AIInfrastructureAutoConfiguration.class)
@ConditionalOnClass(ONNXEmbeddingProvider.class)
@ConditionalOnProperty(
    prefix = "ai.service.features",
    name = "enable-embeddings",
    havingValue = "true",
    matchIfMissing = true
)
public class ONNXAutoConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "ai.providers.embedding-provider", havingValue = "onnx", matchIfMissing = true)
    @ConditionalOnMissingBean(name = "onnxEmbeddingProvider")
    public EmbeddingProvider onnxEmbeddingProvider(AIProviderConfig config) {
        log.info("Creating ONNX Embedding Provider (primary/default)");
        // Availability is determined during the provider's @PostConstruct initialization. Checking here
        // would always report "unavailable" and produce misleading warnings.
        return new ONNXEmbeddingProvider(config);
    }

    @Bean(name = "onnxFallbackEmbeddingProvider")
    @ConditionalOnProperty(name = "ai.providers.enable-fallback", havingValue = "true")
    @ConditionalOnMissingBean(name = "onnxEmbeddingProvider")
    public EmbeddingProvider onnxFallbackEmbeddingProvider(AIProviderConfig config) {
        log.info("Creating ONNX fallback Embedding Provider");
        return new ONNXEmbeddingProvider(config);
    }
}
