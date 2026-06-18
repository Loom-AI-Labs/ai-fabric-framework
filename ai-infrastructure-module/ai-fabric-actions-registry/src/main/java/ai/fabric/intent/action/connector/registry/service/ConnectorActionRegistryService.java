package ai.fabric.intent.action.connector.registry.service;

import ai.fabric.entity.RegisteredConnectorAction;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.connector.ConnectorActionDefinition;
import ai.fabric.repository.RegisteredConnectorActionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.actions.db", name = "enabled", havingValue = "true")
public class ConnectorActionRegistryService {

    private final RegisteredConnectorActionRepository repository;
    private final ConnectorActionDefinitionValidator validator;
    private final AIActionRegistry actionRegistry;

    public List<ConnectorActionDefinition> list() {
        return repository.findAll().stream()
            .sorted(Comparator.comparing(RegisteredConnectorAction::getName, String.CASE_INSENSITIVE_ORDER))
            .map(RegisteredConnectorAction::toDefinition)
            .toList();
    }

    @Transactional
    public ConnectorActionDefinition register(ConnectorActionDefinition definition) {
        try {
            validator.validate(definition);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }

        String actionName = definition.name().trim();
        if (repository.existsByNameIgnoreCase(actionName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Action '" + actionName + "' already exists.");
        }
        if (actionRegistry != null && actionRegistry.findMetadata(actionName).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Action '" + actionName + "' collides with an existing action.");
        }

        RegisteredConnectorAction saved;
        try {
            saved = repository.save(RegisteredConnectorAction.fromDefinition(definition));
            repository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Action '" + actionName + "' already exists.", ex);
        }

        refreshRegistryAfterCommit();

        log.info("Registered connector action '{}' (params={})", saved.getName(), saved.getParams() != null ? saved.getParams().size() : 0);
        return saved.toDefinition();
    }

    @Transactional
    public void deregister(String actionName) {
        if (!StringUtils.hasText(actionName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "actionName is required.");
        }

        Optional<RegisteredConnectorAction> existing = repository.findByNameIgnoreCase(actionName.trim());
        if (existing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Action not found: " + actionName.trim());
        }

        RegisteredConnectorAction entity = existing.get();
        repository.delete(entity);
        repository.flush();

        refreshRegistryAfterCommit();

        log.info("Deregistered connector action '{}'", entity.getName());
    }

    private void refreshRegistryAfterCommit() {
        if (actionRegistry == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            actionRegistry.refresh();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                actionRegistry.refresh();
            }
        });
    }
}
