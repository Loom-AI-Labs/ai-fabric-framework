package ai.fabric.intent.action.connector;

import ai.fabric.intent.action.AIActionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectorActionsRegistryContributorTest {

    @Test
    void getHandlers_shouldAllowMcpOnlyCatalogWithoutGenericConnectorBaseUrl() {
        AIActionConnectorProperties connectorProperties = new AIActionConnectorProperties();
        connectorProperties.getMcpGateway().setBaseUrl("https://mcp-gateway.internal");
        connectorProperties.getMcpGateway().setApiKey("gateway-key");
        ConnectorActionsRegistryContributor contributor = new ConnectorActionsRegistryContributor(
            connectorProperties,
            catalogService("classpath:actions/valid-mcp-actions.yml"),
            null
        );

        List<AIActionHandler> handlers = contributor.getHandlers();

        assertThat(handlers).hasSize(1);
        assertThat(handlers.getFirst().getActionMetadata().getName()).isEqualTo("inventory_search");
    }

    @Test
    void getHandlers_shouldFailFastWhenGenericConnectorActionHasNoBaseUrl() {
        AIActionConnectorProperties connectorProperties = new AIActionConnectorProperties();
        ConnectorActionsRegistryContributor contributor = new ConnectorActionsRegistryContributor(
            connectorProperties,
            catalogService("classpath:actions/valid-actions.yml"),
            null
        );

        assertThatThrownBy(contributor::getHandlers)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ai.actions.connector.baseUrl");
    }

    @Test
    void getHandlers_shouldFailFastWhenMcpGatewayConfigIsMissing() {
        ConnectorActionsRegistryContributor contributor = new ConnectorActionsRegistryContributor(
            new AIActionConnectorProperties(),
            catalogService("classpath:actions/valid-mcp-actions.yml"),
            null
        );

        assertThatThrownBy(contributor::getHandlers)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ai.actions.connector.mcp-gateway.base-url");
    }

    @Test
    void getHandlers_shouldFailFastWhenMcpGatewayApiKeyIsMissing() {
        AIActionConnectorProperties connectorProperties = new AIActionConnectorProperties();
        connectorProperties.getMcpGateway().setBaseUrl("https://mcp-gateway.internal");
        ConnectorActionsRegistryContributor contributor = new ConnectorActionsRegistryContributor(
            connectorProperties,
            catalogService("classpath:actions/valid-mcp-actions.yml"),
            null
        );

        assertThatThrownBy(contributor::getHandlers)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ai.actions.connector.mcp-gateway.api-key");
    }

    private ConnectorActionCatalogService catalogService(String path) {
        AIActionCatalogProperties catalogProperties = new AIActionCatalogProperties();
        AIActionCatalogProperties.ActionSourceProperties source = new AIActionCatalogProperties.ActionSourceProperties();
        source.setType(AIActionCatalogProperties.ActionSourceType.FILE);
        source.setPath(path);
        catalogProperties.setSources(List.of(source));
        return new ConnectorActionCatalogService(
            catalogProperties,
            new ConnectorActionCatalogLoader(new DefaultResourceLoader())
        );
    }
}
