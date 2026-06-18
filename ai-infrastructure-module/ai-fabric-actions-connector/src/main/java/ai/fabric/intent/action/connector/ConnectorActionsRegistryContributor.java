package ai.fabric.intent.action.connector;

import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistryContributor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registers connector-backed actions defined in a file-based action catalog.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorActionsRegistryContributor implements AIActionRegistryContributor {

    private final AIActionConnectorProperties connectorProperties;
    private final ConnectorActionCatalogService catalogService;
    private final ActionConnectorExecutor executor;

    @Override
    public List<AIActionHandler> getHandlers() {
        ConnectorActionCatalog catalog = catalogService != null ? catalogService.getCatalog() : null;
        if (catalog == null) {
            return List.of();
        }
        List<ConnectorActionDefinition> definitions = catalog != null ? catalog.actions() : List.of();

        if (definitions.isEmpty()) {
            return List.of();
        }

        validateExecutionConfiguration(definitions);

        List<AIActionHandler> out = new ArrayList<>();
        for (ConnectorActionDefinition def : definitions) {
            if (def == null) {
                continue;
            }
            AIActionMetaData meta = ConnectorActionMetadataMapper.toMetadata(def);
            Set<String> sensitive = ConnectorActionMetadataMapper.extractSensitiveParams(def);
            out.add(new ConnectorAIActionHandler(
                meta,
                def.requiresConfirmation(),
                def.confirmationMessage(),
                sensitive,
                executor,
                def.llmFacts(),
                def.runtimeActionConfig()
            ));
        }

        log.info("Loaded {} connector action(s) from {} source(s).", out.size(), catalog.sourceLocations().size());
        return List.copyOf(out);
    }

    @Override
    public String getSourceName() {
        return "connector-catalog";
    }

    private void validateExecutionConfiguration(List<ConnectorActionDefinition> definitions) {
        boolean hasStandardConnectorAction = false;
        boolean hasMcpToolAction = false;
        for (ConnectorActionDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            if (isMcpToolAction(definition)) {
                hasMcpToolAction = true;
            } else {
                hasStandardConnectorAction = true;
            }
        }

        if (hasStandardConnectorAction
            && (connectorProperties == null || !StringUtils.hasText(connectorProperties.getBaseUrl()))) {
            throw new IllegalStateException("Connector actions are configured, but ai.actions.connector.baseUrl is missing.");
        }

        if (!hasMcpToolAction) {
            return;
        }
        AIActionConnectorProperties.McpGatewayProperties gateway =
            connectorProperties != null ? connectorProperties.getMcpGateway() : null;
        if (gateway == null || !StringUtils.hasText(gateway.getBaseUrl())) {
            throw new IllegalStateException("MCP tool actions are configured, but ai.actions.connector.mcp-gateway.base-url is missing.");
        }
        if (!StringUtils.hasText(gateway.getApiKey())) {
            throw new IllegalStateException("MCP tool actions are configured, but ai.actions.connector.mcp-gateway.api-key is missing.");
        }
    }

    private boolean isMcpToolAction(ConnectorActionDefinition definition) {
        if (definition == null) {
            return false;
        }
        if (StringUtils.hasText(definition.adapterType())
            && "mcp-tool".equalsIgnoreCase(definition.adapterType().trim())) {
            return true;
        }
        Map<String, Object> execution = definition.execution();
        if (execution == null || execution.isEmpty()) {
            return false;
        }
        Object adapterType = execution.get("adapterType");
        if (adapterType != null && "mcp-tool".equalsIgnoreCase(adapterType.toString().trim())) {
            return true;
        }
        return execution.containsKey("mcp");
    }
}
