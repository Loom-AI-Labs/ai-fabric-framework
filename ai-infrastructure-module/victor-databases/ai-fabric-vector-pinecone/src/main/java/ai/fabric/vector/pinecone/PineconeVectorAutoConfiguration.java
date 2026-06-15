package ai.fabric.vector.pinecone;

import ai.fabric.config.AIInfrastructureAutoConfiguration;
import ai.fabric.config.AIProviderConfig;
import ai.fabric.config.VectorDatabaseConfig;
import ai.fabric.rag.VectorDatabaseService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Auto-configuration for Pinecone-backed vector database integration.
 */
@AutoConfiguration
@AutoConfigureAfter(AIInfrastructureAutoConfiguration.class)
@ConditionalOnClass(PineconeVectorDatabaseService.class)
public class PineconeVectorAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "ai.vector-db.type", havingValue = "pinecone")
    public PineconeVectorDatabaseService pineconeVectorDatabaseDelegate(AIProviderConfig config,
                                                                        ObjectProvider<VectorDatabaseConfig> vectorDatabaseConfig,
                                                                        Environment environment) {
        AIProviderConfig.PineconeConfig pinecone = config.getPinecone();

        // Relationship-query realapi tests (and some apps) configure Pinecone under `ai.vector-db.pinecone.*`.
        // Map those values into the shared provider config so the Pinecone module works in both styles.
        if (pinecone != null) {
            pinecone.setEnabled(true);

            String apiKey = firstNonBlank(pinecone.getApiKey(), environment.getProperty("ai.vector-db.pinecone.api-key"));
            if (StringUtils.hasText(apiKey)) {
                pinecone.setApiKey(apiKey);
            }

            String apiHost = firstNonBlank(pinecone.getApiHost(), environment.getProperty("ai.vector-db.pinecone.api-host"));
            if (StringUtils.hasText(apiHost)) {
                pinecone.setApiHost(apiHost);
            }

            String indexName = firstNonBlank(pinecone.getIndexName(), environment.getProperty("ai.vector-db.pinecone.index-name"));
            if (StringUtils.hasText(indexName)) {
                pinecone.setIndexName(indexName);
            }

            String env = firstNonBlank(pinecone.getEnvironment(), environment.getProperty("ai.vector-db.pinecone.environment"));
            if (StringUtils.hasText(env)) {
                pinecone.setEnvironment(env);
            }

            Integer dims = environment.getProperty("ai.vector-db.pinecone.dimensions", Integer.class);
            if (pinecone.getDimensions() == null && dims != null && dims > 0) {
                pinecone.setDimensions(dims);
            }
        }

        return new PineconeVectorDatabaseService(config, vectorDatabaseConfig.getIfAvailable());
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "ai.vector-db.type", havingValue = "pinecone")
    @ConditionalOnMissingBean(VectorDatabaseService.class)
    public VectorDatabaseService pineconeVectorDatabaseService(PineconeVectorDatabaseService delegate) {
        return delegate;
    }

    private static String firstNonBlank(String first, String second) {
        if (StringUtils.hasText(first)) {
            return first;
        }
        return StringUtils.hasText(second) ? second : null;
    }
}
