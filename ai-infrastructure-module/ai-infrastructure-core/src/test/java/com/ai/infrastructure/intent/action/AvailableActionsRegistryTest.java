package com.ai.infrastructure.intent.action;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AvailableActionsRegistryTest {

    @Test
    void shouldAggregateActionsFromProvidersAndDeduplicateByName() {
        AIActionProvider providerA = () -> List.of(
            ActionInfo.builder()
                .name("cancel_subscription")
                .description("Cancel active subscription")
                .category("subscription")
                .parameters(Map.of("reason", "string"))
                .build(),
            ActionInfo.builder()
                .name("upgrade_subscription")
                .description("Upgrade plan")
                .category("subscription")
                .build()
        );

        AIActionProvider providerB = () -> List.of(
            ActionInfo.builder()
                .name("cancel_subscription") // duplicate
                .description("Duplicate definition should be ignored")
                .category("subscription")
                .build(),
            ActionInfo.builder()
                .name("update_payment_method")
                .description("Update stored payment method")
                .category("payment")
                .parameters(Map.of("payment_method_id", "string"))
                .build()
        );

        AvailableActionsRegistry registry = new AvailableActionsRegistry(List.of(providerA, providerB));

        List<ActionInfo> actions = registry.getAllAvailableActions();

        assertThat(actions)
            .hasSize(3)
            .extracting(ActionInfo::getName)
            .containsExactly("cancel_subscription", "upgrade_subscription", "update_payment_method");

        assertThat(actions.getFirst().getDescription()).isEqualTo("Cancel active subscription");
    }

    @Test
    void shouldFindActionByNameCaseInsensitive() {
        AIActionProvider provider = () -> List.of(
            ActionInfo.builder().name("PAUSE_SUBSCRIPTION").description("Pause subscription").build()
        );

        AvailableActionsRegistry registry = new AvailableActionsRegistry(List.of(provider));

        assertThat(registry.findByName("pause_subscription"))
            .isPresent()
            .get()
            .extracting(ActionInfo::getDescription)
            .isEqualTo("Pause subscription");
    }

    @Test
    void shouldReturnEmptySummaryIfNoProviders() {
        AvailableActionsRegistry registry = new AvailableActionsRegistry(List.of());

        assertThat(registry.getAllAvailableActions()).isEmpty();
        assertThat(registry.describeRegistry()).isEqualTo("AvailableActionsRegistry[providers=0, actions=0]");
    }
}
