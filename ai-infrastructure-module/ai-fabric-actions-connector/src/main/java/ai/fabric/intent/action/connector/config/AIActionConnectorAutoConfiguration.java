package ai.fabric.intent.action.connector.config;

import ai.fabric.config.AIInfrastructureAutoConfiguration;
import ai.fabric.intent.action.connector.AIActionCatalogProperties;
import ai.fabric.intent.action.connector.AIActionConnectorProperties;
import ai.fabric.intent.action.connector.McpActionExecutor;
import ai.fabric.intent.action.connector.springai.SpringAiMcpActionExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Auto-configuration for connector-backed actions.
 *
 * <p>This module provides:</p>
 * <ul>
 *   <li>file-based connector action catalogs (V1)</li>
 *   <li>connector execution via the Customer Connector API</li>
 * </ul>
 */
@AutoConfiguration
@AutoConfigureAfter(AIInfrastructureAutoConfiguration.class)
@EnableConfigurationProperties({
    AIActionCatalogProperties.class,
    AIActionConnectorProperties.class
})
public class AIActionConnectorAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.modelcontextprotocol.client.McpSyncClient")
    static class SpringAiMcpBridgeConfiguration {

        @Bean
        @ConditionalOnMissingBean(McpActionExecutor.class)
        McpActionExecutor springAiMcpActionExecutor(ObjectProvider<List<McpSyncClient>> mcpSyncClientsProvider,
                                                    ObjectProvider<ObjectMapper> objectMapperProvider) {
            ObjectMapper objectMapper = objectMapperProvider != null
                ? objectMapperProvider.getIfAvailable(ObjectMapper::new)
                : new ObjectMapper();
            return new SpringAiMcpActionExecutor(
                () -> mcpSyncClientsProvider != null ? mcpSyncClientsProvider.getIfAvailable(List::of) : List.of(),
                objectMapper
            );
        }
    }
}
