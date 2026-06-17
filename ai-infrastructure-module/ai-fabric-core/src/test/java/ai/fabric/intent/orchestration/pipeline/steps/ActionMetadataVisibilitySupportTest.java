package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.ActionAccessMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionMetadataVisibilitySupportTest {

    @Test
    void shouldFilterHiddenSystemAndSecretParametersFromPublicMetadata() {
        AIActionMetaData metadata = AIActionMetaData.builder()
            .name("cancel_order")
            .displayName("Cancel order")
            .description("Cancel an order")
            .category("orders")
            .accessMode(ActionAccessMode.WRITE_ONLY)
            .anonymousAllowed(true)
            .confirmationRequired(true)
            .groundingEligible(true)
            .readActionResolutionEligible(true)
            .parameters(Map.of(
                "orderId", "Order id",
                "shopperSessionId", "Session id",
                "token", "Secret token",
                "internalNote", "Internal note"
            ))
            .parameterSchemas(Map.of(
                "orderId", AIActionParamSchema.builder().name("orderId").build(),
                "token", AIActionParamSchema.builder().name("token").visibility("SECRET").build(),
                "internalNote", AIActionParamSchema.builder().name("internalNote").askUser(false).build()
            ))
            .requiredParameters(Set.of("orderId", "shopperSessionId", "token", "internalNote"))
            .build();

        AIActionMetaData publicMetadata = ActionMetadataVisibilitySupport.publicActionMetadata(metadata);

        assertThat(publicMetadata.getName()).isEqualTo("cancel_order");
        assertThat(publicMetadata.getParameters()).containsOnlyKeys("orderId");
        assertThat(publicMetadata.getParameterSchemas()).containsOnlyKeys("orderId");
        assertThat(publicMetadata.getRequiredParameters()).containsExactlyInAnyOrder("orderId");
        assertThat(publicMetadata.isAnonymousAllowed()).isTrue();
        assertThat(publicMetadata.isConfirmationRequired()).isTrue();
        assertThat(publicMetadata.isGroundingEligible()).isTrue();
        assertThat(publicMetadata.isReadActionResolutionEligible()).isTrue();
        assertThatThrownBy(() -> publicMetadata.getParameters().put("x", "y"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldFilterProvidedParametersWithSameVisibilityRules() {
        AIActionMetaData metadata = AIActionMetaData.builder()
            .parameters(Map.of(
                "orderId", "Order id",
                "shopperSessionId", "Session id",
                "token", "Secret token"
            ))
            .parameterSchemas(Map.of(
                "token", AIActionParamSchema.builder().visibility("SECRET").build()
            ))
            .build();

        Map<String, Object> publicValues = ActionMetadataVisibilitySupport.publicProvidedParameters(
            metadata,
            Map.of("orderId", "ord-1", "shopperSessionId", "session-1", "token", "secret")
        );

        assertThat(publicValues).containsOnly(Map.entry("orderId", "ord-1"));
    }

    @Test
    void shouldResolveParameterAndMetadataPresenceHelpers() {
        AIActionMetaData metadata = AIActionMetaData.builder()
            .parameters(Map.of("orderId", "Order id"))
            .requiredParameters(Set.of("reason"))
            .build();

        assertThat(ActionMetadataVisibilitySupport.hasActionParameter(metadata, "orderId")).isTrue();
        assertThat(ActionMetadataVisibilitySupport.hasActionParameter(metadata, "reason")).isTrue();
        assertThat(ActionMetadataVisibilitySupport.hasActionParameter(metadata, "missing")).isFalse();

        assertThat(ActionMetadataVisibilitySupport.hasParamValue(Map.of("orderId", "ord-1"), "orderId")).isTrue();
        assertThat(ActionMetadataVisibilitySupport.hasParamValue(Map.of("orderId", List.of()), "orderId")).isFalse();

        assertThat(ActionMetadataVisibilitySupport.getMetadataValueIgnoreCase(
            Map.of("Cart_ID", "cart-1"),
            "cart_id"
        )).isEqualTo("cart-1");
        assertThat(ActionMetadataVisibilitySupport.getMetadataValueIgnoreCase(Map.of("Cart_ID", "cart-1"), "other"))
            .isNull();
    }
}
