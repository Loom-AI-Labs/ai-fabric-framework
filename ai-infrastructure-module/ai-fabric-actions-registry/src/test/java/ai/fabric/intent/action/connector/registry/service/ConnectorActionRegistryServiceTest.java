package ai.fabric.intent.action.connector.registry.service;

import ai.fabric.entity.RegisteredConnectorAction;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionResultPresentationHint;
import ai.fabric.intent.action.connector.ConnectorActionDefinition;
import ai.fabric.intent.action.connector.ConnectorActionParamDefinition;
import ai.fabric.repository.RegisteredConnectorActionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConnectorActionRegistryServiceTest {

    @Test
    void register_translatesValidationFailureToBadRequest() {
        RegisteredConnectorActionRepository repository = mock(RegisteredConnectorActionRepository.class);
        ConnectorActionDefinitionValidator validator = mock(ConnectorActionDefinitionValidator.class);
        doThrow(new IllegalArgumentException("bad action contract"))
            .when(validator)
            .validate(any(ConnectorActionDefinition.class));
        ConnectorActionRegistryService service = new ConnectorActionRegistryService(repository, validator, null);

        assertThatThrownBy(() -> service.register(validDefinition()))
            .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(ex.getReason()).isEqualTo("bad action contract");
            });
        verifyNoInteractions(repository);
    }

    @Test
    void register_refreshesActionRegistryAfterCommitWhenSynchronizationIsActive() {
        RegisteredConnectorActionRepository repository = mock(RegisteredConnectorActionRepository.class);
        ConnectorActionDefinitionValidator validator = mock(ConnectorActionDefinitionValidator.class);
        AIActionRegistry actionRegistry = mock(AIActionRegistry.class);
        ConnectorActionDefinition definition = validDefinition();
        when(repository.existsByNameIgnoreCase("create_order")).thenReturn(false);
        when(repository.save(any(RegisteredConnectorAction.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(actionRegistry.findMetadata("create_order")).thenReturn(Optional.empty());
        ConnectorActionRegistryService service = new ConnectorActionRegistryService(repository, validator, actionRegistry);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.register(definition);

            verify(actionRegistry, never()).refresh();
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            verify(actionRegistry).refresh();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private ConnectorActionDefinition validDefinition() {
        return new ConnectorActionDefinition(
            "create_order",
            "create_order",
            "Create an order",
            "commerce",
            ActionAccessMode.WRITE_ONLY,
            true,
            "Create order for {{sku}}?",
            List.of(new ConnectorActionParamDefinition(
                "sku",
                "SKU",
                AIActionParamType.STRING,
                true,
                false,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                false,
                null,
                Map.of(),
                List.of(),
                false,
                List.of(),
                null
            )),
            false,
            false,
            false,
            ActionResultPresentationHint.STATUS,
            null,
            null,
            null,
            List.of(),
            null
        );
    }
}
