package ai.fabric.intent.action.connector.registry.source;

import ai.fabric.entity.RegisteredConnectorAction;
import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistryContributor;
import ai.fabric.intent.action.connector.AIActionConnectorProperties;
import ai.fabric.intent.action.connector.ActionConnectorExecutor;
import ai.fabric.intent.action.connector.ConnectorAIActionHandler;
import ai.fabric.intent.action.connector.ConnectorActionDefinition;
import ai.fabric.intent.action.connector.ConnectorActionMetadataMapper;
import ai.fabric.intent.action.connector.registry.service.ConnectorActionDefinitionValidator;
import ai.fabric.repository.RegisteredConnectorActionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.actions.db", name = "enabled", havingValue = "true")
public class DbConnectorActionsRegistryContributor implements AIActionRegistryContributor {

    private final RegisteredConnectorActionRepository repository;
    private final AIActionConnectorProperties connectorProperties;
    private final ActionConnectorExecutor executor;
    private final ConnectorActionDefinitionValidator validator;

    @Override
    public List<AIActionHandler> getHandlers() {
        List<RegisteredConnectorAction> registered = repository != null ? repository.findAll() : List.of();
        if (registered.isEmpty()) {
            return List.of();
        }

        if (connectorProperties == null || !StringUtils.hasText(connectorProperties.getBaseUrl())) {
            throw new IllegalStateException("DB connector actions are registered, but ai.actions.connector.baseUrl is missing.");
        }

        List<AIActionHandler> out = new ArrayList<>();
        for (RegisteredConnectorAction entity : registered) {
            ConnectorActionDefinition def = entity != null ? entity.toDefinition() : null;
            if (def == null) {
                continue;
            }
            if (!isValid(def)) {
                continue;
            }
            AIActionMetaData meta = ConnectorActionMetadataMapper.toMetadata(def);
            Set<String> sensitive = ConnectorActionMetadataMapper.extractSensitiveParams(def);
            out.add(new ConnectorAIActionHandler(
                meta,
                def.requiresConfirmation(),
                def.confirmationMessage(),
                sensitive,
                executor
            ));
        }

        log.info("Loaded {} connector action(s) from DB.", out.size());
        return List.copyOf(out);
    }

    private boolean isValid(ConnectorActionDefinition definition) {
        try {
            validator.validate(definition);
            return true;
        } catch (IllegalArgumentException ex) {
            String actionName = definition != null && StringUtils.hasText(definition.name())
                ? definition.name().trim()
                : "(unnamed)";
            log.warn("Skipping DB connector action '{}' because its stored definition is invalid: {}",
                actionName,
                ex.getMessage());
            return false;
        }
    }

    @Override
    public String getSourceName() {
        return "connector-db";
    }
}
