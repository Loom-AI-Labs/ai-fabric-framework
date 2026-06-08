package com.ai.infrastructure.vector.lucene;

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
 * Auto-configuration for Lucene-backed vector database support.
 */
@AutoConfiguration
@AutoConfigureAfter(AIInfrastructureAutoConfiguration.class)
@ConditionalOnClass(LuceneVectorDatabaseService.class)
public class LuceneVectorAutoConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "ai.vector-db.type", havingValue = "lucene", matchIfMissing = true)
    public LuceneVectorDatabaseService luceneVectorDatabaseDelegate(AIProviderConfig config) {
        return new LuceneVectorDatabaseService(config);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "ai.vector-db.type", havingValue = "lucene", matchIfMissing = true)
    @ConditionalOnMissingBean(VectorDatabaseService.class)
    public VectorDatabaseService luceneVectorDatabaseService(LuceneVectorDatabaseService delegate) {
        return delegate;
    }
}
