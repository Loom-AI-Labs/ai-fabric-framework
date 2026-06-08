package com.ai.infrastructure.vector.milvus;

import com.ai.infrastructure.config.AIInfrastructureAutoConfiguration;
import com.ai.infrastructure.config.AIProviderConfig;
import com.ai.infrastructure.rag.VectorDatabaseService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Auto-configuration for Milvus vector database integration.
 */
@AutoConfiguration
@AutoConfigureAfter(AIInfrastructureAutoConfiguration.class)
@ConditionalOnClass(MilvusVectorDatabaseService.class)
public class MilvusVectorAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "ai.vector-db.type", havingValue = "milvus")
    public MilvusVectorDatabaseService milvusVectorDatabaseDelegate(AIProviderConfig providerConfig) {
        return new MilvusVectorDatabaseService(providerConfig);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "ai.vector-db.type", havingValue = "milvus")
    @ConditionalOnMissingBean(VectorDatabaseService.class)
    public VectorDatabaseService milvusVectorDatabaseService(MilvusVectorDatabaseService delegate) {
        return delegate;
    }
}
