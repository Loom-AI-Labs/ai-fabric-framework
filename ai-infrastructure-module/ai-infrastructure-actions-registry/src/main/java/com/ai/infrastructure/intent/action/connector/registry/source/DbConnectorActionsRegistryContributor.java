package com.ai.infrastructure.intent.action.connector.registry.source;

import com.ai.infrastructure.entity.RegisteredConnectorAction;
import com.ai.infrastructure.intent.action.AIActionHandler;
import com.ai.infrastructure.intent.action.AIActionMetaData;
import com.ai.infrastructure.intent.action.AIActionRegistryContributor;
import com.ai.infrastructure.intent.action.connector.AIActionConnectorProperties;
import com.ai.infrastructure.intent.action.connector.ActionConnectorExecutor;
import com.ai.infrastructure.intent.action.connector.ConnectorAIActionHandler;
import com.ai.infrastructure.intent.action.connector.ConnectorActionDefinition;
import com.ai.infrastructure.intent.action.connector.ConnectorActionMetadataMapper;
import com.ai.infrastructure.repository.RegisteredConnectorActionRepository;
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

    @Override
    public String getSourceName() {
        return "connector-db";
    }
}

