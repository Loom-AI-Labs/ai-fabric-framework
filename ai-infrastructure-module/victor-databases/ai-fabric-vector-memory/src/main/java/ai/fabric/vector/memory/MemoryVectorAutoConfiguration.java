package ai.fabric.vector.memory;

import ai.fabric.config.AIInfrastructureAutoConfiguration;
import ai.fabric.config.AIProviderConfig;
import ai.fabric.config.VectorDatabaseConfig;
import ai.fabric.rag.VectorDatabaseService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Locale;

/**
 * Auto-configuration for in-memory vector database integration.
 */
@AutoConfiguration
@AutoConfigureAfter(AIInfrastructureAutoConfiguration.class)
@ConditionalOnClass(InMemoryVectorDatabaseService.class)
public class MemoryVectorAutoConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "ai.vector-db.type", havingValue = "memory")
    @ConditionalOnMissingBean(VectorDatabaseService.class)
    public InMemoryVectorDatabaseService inMemoryVectorDatabaseService(
        AIProviderConfig config,
        ObjectProvider<VectorDatabaseConfig> vectorDatabaseConfig,
        Environment environment
    ) {
        assertProductionUseAllowed(vectorDatabaseConfig.getIfAvailable(VectorDatabaseConfig::new), environment);
        return new InMemoryVectorDatabaseService(config);
    }

    private void assertProductionUseAllowed(VectorDatabaseConfig vectorDatabaseConfig, Environment environment) {
        boolean productionProfile = Arrays.stream(environment.getActiveProfiles())
            .filter(profile -> profile != null && !profile.isBlank())
            .map(profile -> profile.toLowerCase(Locale.ROOT))
            .anyMatch(profile -> profile.equals("prod") || profile.equals("production"));

        boolean allowInProduction = vectorDatabaseConfig != null
            && vectorDatabaseConfig.getMemory() != null
            && Boolean.TRUE.equals(vectorDatabaseConfig.getMemory().getAllowInProduction());

        if (productionProfile && !allowInProduction) {
            throw new IllegalStateException(
                "ai.vector-db.type=memory is intended for development and tests. "
                    + "Set ai.vector-db.memory.allow-in-production=true to acknowledge non-durable production use."
            );
        }
    }
}
