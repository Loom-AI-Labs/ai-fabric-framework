package ai.fabric.intent.action;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegisteredActionsAIActionProviderTest {

    @Test
    void describesTrustedTargetRequirementWithoutExposingInternalParameter() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionMetaData metadata = AIActionMetaData.builder()
            .name("get_item")
            .description("Returns details for the current item.")
            .accessMode(ActionAccessMode.READ)
            .groundingEligible(true)
            .requiredParameters(Set.of("itemId"))
            .parameters(Map.of("itemId", "Internal item identifier"))
            .parameterSchemas(Map.of(
                "itemId",
                AIActionParamSchema.builder()
                    .name("itemId")
                    .required(true)
                    .visibility("INTERNAL")
                    .askUser(false)
                    .evidenceBound(true)
                    .resolveFrom(Map.of("source", "ATTACHMENT_METADATA"))
                    .build()
            ))
            .build();
        when(registry.getAllMetadata()).thenReturn(List.of(metadata));

        ActionInfo action = new RegisteredActionsAIActionProvider(registry).getAvailableActions().getFirst();

        assertThat(action.getDescription())
            .contains("Returns details for the current item.")
            .contains("Requires a trusted current target")
            .contains("use information retrieval or another search action instead");
        assertThat(action.getParameters()).doesNotContainKey("itemId");
        assertThat(action.getParameterSchemas()).doesNotContainKey("itemId");
    }

    @Test
    void leavesOrdinaryActionDescriptionUnchanged() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionMetaData metadata = AIActionMetaData.builder()
            .name("search_items")
            .description("Searches current items.")
            .accessMode(ActionAccessMode.READ)
            .groundingEligible(true)
            .build();
        when(registry.getAllMetadata()).thenReturn(List.of(metadata));

        ActionInfo action = new RegisteredActionsAIActionProvider(registry).getAvailableActions().getFirst();

        assertThat(action.getDescription()).isEqualTo("Searches current items.");
    }
}
