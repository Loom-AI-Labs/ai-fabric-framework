package com.ai.infrastructure.relationship.integration;

import com.ai.infrastructure.config.AIProviderConfig;
import com.ai.infrastructure.core.AIEmbeddingService;
import com.ai.infrastructure.provider.onnx.ONNXEmbeddingProvider;
import com.ai.infrastructure.rag.VectorDatabaseService;
import com.ai.infrastructure.relationship.service.RelationshipQueryPlanner;
import com.ai.infrastructure.vector.lucene.LuceneVectorDatabaseService;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class RelationshipQueryIntegrationTestBeans {

    @Bean
    ONNXEmbeddingProvider onnxEmbeddingProvider(AIProviderConfig config) {
        return new ONNXEmbeddingProvider(config);
    }

    @Bean
    AIEmbeddingService aiEmbeddingService(
        AIProviderConfig config,
        ONNXEmbeddingProvider onnxEmbeddingProvider,
        CacheManager cacheManager
    ) {
        return new AIEmbeddingService(config, onnxEmbeddingProvider, cacheManager, null);
    }

    @Bean
    @ConditionalOnMissingBean(VectorDatabaseService.class)
    VectorDatabaseService vectorDatabaseService(AIProviderConfig config) {
        return new LuceneVectorDatabaseService(config);
    }

    @Bean
    RelationshipQueryPlanner relationshipQueryPlanner() {
        return Mockito.mock(RelationshipQueryPlanner.class);
    }
}

