package ai.fabric.intent.retrieval.connector.config;

import ai.fabric.config.AIInfrastructureAutoConfiguration;
import ai.fabric.intent.retrieval.connector.AIRetrievalConnectorProperties;
import ai.fabric.intent.retrieval.connector.RetrievalDocumentSanitizer;
import ai.fabric.intent.retrieval.connector.RetrievalConnectorRAGProvider;
import ai.fabric.http.AIHttpClientFactory;
import ai.fabric.spi.RAGProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;

/**
 * Auto-configuration for documents-only external retrieval via the Customer Connector API.
 *
 * <p>When enabled, this provides a {@link ai.fabric.spi.RAGProvider} implementation that calls
 * {@code POST /retrieval/search} and returns documents/chunks only.</p>
 */
@AutoConfiguration
@AutoConfigureAfter(AIInfrastructureAutoConfiguration.class)
@AutoConfigureBefore(name = "ai.fabric.rag.config.RAGAutoConfiguration")
@EnableConfigurationProperties(AIRetrievalConnectorProperties.class)
@ConditionalOnProperty(prefix = "ai.retrieval.connector", name = "enabled", havingValue = "true")
public class AIRetrievalConnectorAutoConfiguration {

    @Bean
    @Primary
    @ConditionalOnMissingBean(RAGProvider.class)
    public RetrievalConnectorRAGProvider retrievalConnectorRAGProvider(AIRetrievalConnectorProperties properties,
                                                                       AIHttpClientFactory httpClientFactory,
                                                                       ObjectProvider<ObjectMapper> objectMapperProvider,
                                                                       ObjectProvider<Clock> clockProvider,
                                                                       ObjectProvider<RetrievalDocumentSanitizer> sanitizerProvider) {
        Clock clock = clockProvider.getIfAvailable(Clock::systemUTC);
        return new RetrievalConnectorRAGProvider(
            properties,
            httpClientFactory,
            objectMapperProvider,
            clock,
            sanitizerProvider.orderedStream().toList()
        );
    }
}
