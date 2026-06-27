package ai.fabric.intent.action.connector.registry.source;

import ai.fabric.entity.RegisteredConnectorAction;
import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionResultPresentationHint;
import ai.fabric.intent.action.connector.AIActionConnectorProperties;
import ai.fabric.intent.action.connector.ActionConnectorExecutor;
import ai.fabric.intent.action.connector.ConnectorActionDefinition;
import ai.fabric.intent.action.connector.ConnectorActionParamDefinition;
import ai.fabric.intent.action.connector.registry.service.ConnectorActionDefinitionValidator;
import ai.fabric.repository.RegisteredConnectorActionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DbConnectorActionsRegistryContributorTest {

    @Test
    void getHandlers_shouldValidateStoredDefinitionsAndSkipInvalidRows() {
        RegisteredConnectorActionRepository repository = mock(RegisteredConnectorActionRepository.class);
        AIActionConnectorProperties properties = connectorProperties();
        DbConnectorActionsRegistryContributor contributor = new DbConnectorActionsRegistryContributor(
            repository,
            properties,
            mock(ActionConnectorExecutor.class),
            new ConnectorActionDefinitionValidator()
        );
        when(repository.findAll()).thenReturn(List.of(
            RegisteredConnectorAction.fromDefinition(action("create_order", List.of(param("sku")))),
            RegisteredConnectorAction.fromDefinition(action("broken_order", List.of(param("sku"), param("SKU"))))
        ));

        List<AIActionHandler> handlers = contributor.getHandlers();

        assertThat(handlers).hasSize(1);
        assertThat(handlers.getFirst().getActionMetadata().getName()).isEqualTo("create_order");
    }

    @Test
    void getHandlers_shouldFailFastWhenDbActionsExistButConnectorBaseUrlIsMissing() {
        RegisteredConnectorActionRepository repository = mock(RegisteredConnectorActionRepository.class);
        AIActionConnectorProperties properties = new AIActionConnectorProperties();
        DbConnectorActionsRegistryContributor contributor = new DbConnectorActionsRegistryContributor(
            repository,
            properties,
            mock(ActionConnectorExecutor.class),
            new ConnectorActionDefinitionValidator()
        );
        when(repository.findAll()).thenReturn(List.of(
            RegisteredConnectorAction.fromDefinition(action("create_order", List.of(param("sku"))))
        ));

        assertThatThrownBy(contributor::getHandlers)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ai.actions.connector.baseUrl");
    }

    private static AIActionConnectorProperties connectorProperties() {
        AIActionConnectorProperties properties = new AIActionConnectorProperties();
        properties.setBaseUrl("https://connector.example");
        return properties;
    }

    private static ConnectorActionDefinition action(String name, List<ConnectorActionParamDefinition> params) {
        return new ConnectorActionDefinition(
            name,
            name,
            "Create order",
            "commerce",
            ActionAccessMode.WRITE_ONLY,
            true,
            "Create order for {{sku}}?",
            params,
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

    private static ConnectorActionParamDefinition param(String name) {
        return new ConnectorActionParamDefinition(
            name,
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
        );
    }
}
