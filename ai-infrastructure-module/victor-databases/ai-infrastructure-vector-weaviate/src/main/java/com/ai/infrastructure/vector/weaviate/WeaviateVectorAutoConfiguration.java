package com.ai.infrastructure.vector.weaviate;

import com.ai.infrastructure.config.AIInfrastructureAutoConfiguration;
import com.ai.infrastructure.config.AIProviderConfig;
import com.ai.infrastructure.config.VectorDatabaseConfig;
import com.ai.infrastructure.rag.VectorDatabaseService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Auto-configuration for Weaviate vector database integration.
 */
@AutoConfiguration
@AutoConfigureAfter(AIInfrastructureAutoConfiguration.class)
@ConditionalOnClass(WeaviateVectorDatabaseService.class)
public class WeaviateVectorAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "ai.vector-db.type", havingValue = "weaviate")
    public WeaviateVectorDatabaseService weaviateVectorDatabaseDelegate(AIProviderConfig providerConfig,
                                                                        ObjectProvider<VectorDatabaseConfig> vectorDatabaseConfig) {
        return new WeaviateVectorDatabaseService(providerConfig, vectorDatabaseConfig.getIfAvailable());
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "ai.vector-db.type", havingValue = "weaviate")
    @ConditionalOnMissingBean(VectorDatabaseService.class)
    public VectorDatabaseService weaviateVectorDatabaseService(WeaviateVectorDatabaseService delegate) {
        return delegate;
    }
}
