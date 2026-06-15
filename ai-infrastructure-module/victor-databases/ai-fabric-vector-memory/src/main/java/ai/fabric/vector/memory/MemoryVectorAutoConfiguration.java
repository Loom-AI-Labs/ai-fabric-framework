package ai.fabric.vector.memory;

import ai.fabric.config.AIInfrastructureAutoConfiguration;
import ai.fabric.config.AIProviderConfig;
import ai.fabric.rag.VectorDatabaseService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Auto-configuration for in-memory vector database integration.
 */
@AutoConfiguration
@AutoConfigureAfter(AIInfrastructureAutoConfiguration.class)
@ConditionalOnClass(InMemoryVectorDatabaseService.class)
public class MemoryVectorAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "ai.vector-db.type", havingValue = "memory")
    public InMemoryVectorDatabaseService inMemoryVectorDatabaseDelegate(AIProviderConfig config) {
        return new InMemoryVectorDatabaseService(config);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "ai.vector-db.type", havingValue = "memory")
    @ConditionalOnMissingBean(VectorDatabaseService.class)
    public VectorDatabaseService inMemoryVectorDatabaseService(InMemoryVectorDatabaseService delegate) {
        return delegate;
    }
}
