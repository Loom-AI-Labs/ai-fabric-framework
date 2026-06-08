package com.ai.infrastructure.vector.qdrant;

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
 * Auto-configuration for Qdrant vector database integration.
 */
@AutoConfiguration
@AutoConfigureAfter(AIInfrastructureAutoConfiguration.class)
@ConditionalOnClass(QdrantVectorDatabaseService.class)
public class QdrantVectorAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "ai.vector-db.type", havingValue = "qdrant")
    public QdrantVectorDatabaseService qdrantVectorDatabaseDelegate(AIProviderConfig providerConfig,
                                                                    ObjectProvider<VectorDatabaseConfig> vectorDatabaseConfig) {
        return new QdrantVectorDatabaseService(providerConfig, vectorDatabaseConfig.getIfAvailable());
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "ai.vector-db.type", havingValue = "qdrant")
    @ConditionalOnMissingBean(VectorDatabaseService.class)
    public VectorDatabaseService qdrantVectorDatabaseService(QdrantVectorDatabaseService delegate) {
        return delegate;
    }
}
