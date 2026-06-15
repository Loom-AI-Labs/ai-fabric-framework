package ai.fabric.relationship.integration;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.provider.onnx.ONNXEmbeddingProvider;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.relationship.service.RelationshipQueryPlanner;
import ai.fabric.vector.lucene.LuceneVectorDatabaseService;
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

