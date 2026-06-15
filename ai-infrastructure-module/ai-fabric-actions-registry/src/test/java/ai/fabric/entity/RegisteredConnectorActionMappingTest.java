package ai.fabric.entity;

import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.action.ActionResultPresentationHint;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.AIContributionProvenance;
import ai.fabric.intent.action.connector.ConnectorActionDefinition;
import ai.fabric.intent.action.connector.ConnectorActionParamDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RegisteredConnectorActionMappingTest {

    @Test
    void fromDefinition_toDefinition_roundTrips() {
        ConnectorActionDefinition definition = new ConnectorActionDefinition(
            "create_order",
            "Create Order",
            "Create an order",
            "commerce",
            ActionAccessMode.WRITE_ONLY,
            true,
            "Create order for {{sku}}?",
            List.of(
                new ConnectorActionParamDefinition(
                    "sku",
                    "SKU",
                    AIActionParamType.STRING,
                    true,
                    false,
                    "^[A-Z0-9_-]{3,64}$",
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
                ),
                new ConnectorActionParamDefinition(
                    "quantity",
                    "Quantity",
                    AIActionParamType.INTEGER,
                    true,
                    false,
                    null,
                    List.of(),
                    1L,
                    100L,
                    1,
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
                )
            ),
            true,
            false,
            false,
            ActionResultPresentationHint.STATUS,
            "orders-module",
            "order-status-card",
            AIContributionProvenance.builder()
                .contributionType("ACTION")
                .sourceType("ACTION_CATALOG")
                .sourceId("create_order")
                .sourceLocation("classpath:test-actions.yml")
                .build(),
            List.of(),
            null
        );

        RegisteredConnectorAction entity = RegisteredConnectorAction.fromDefinition(definition);
        ConnectorActionDefinition roundTrip = entity.toDefinition();

        assertThat(roundTrip.name()).isEqualTo(definition.name());
        assertThat(roundTrip.accessMode()).isEqualTo(definition.accessMode());
        assertThat(roundTrip.anonymousAllowed()).isEqualTo(definition.anonymousAllowed());
        assertThat(roundTrip.params()).hasSize(2);
        assertThat(roundTrip.params().getFirst().name()).isEqualTo("sku");
        assertThat(roundTrip.params().getLast().name()).isEqualTo("quantity");
        assertThat(roundTrip.params().getLast().defaultValue()).isEqualTo("1");
    }
}
