package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ActionParameterSupportTest {

    @Test
    void shouldNormalizeParameterNameSet() {
        assertThat(ActionParameterSupport.normalizeParameterNameSet(Set.of(" Sku ", "sku", "CartId")))
            .containsExactlyInAnyOrder("sku", "cartid");
        assertThat(ActionParameterSupport.normalizeParameterNameSet(null)).isEmpty();
    }

    @Test
    void shouldResolveParameterSchemaCaseInsensitively() {
        AIActionParamSchema schema = AIActionParamSchema.builder()
            .name("productId")
            .build();
        AIActionMetaData meta = AIActionMetaData.builder()
            .parameterSchemas(Map.of("productId", schema))
            .build();

        assertThat(ActionParameterSupport.paramSchema(meta, " productid ")).isSameAs(schema);
        assertThat(ActionParameterSupport.paramSchema(meta, "missing")).isNull();
    }

    @Test
    void shouldHideSystemInternalSecretAndAskUserFalseParameters() {
        AIActionMetaData meta = AIActionMetaData.builder()
            .parameterSchemas(Map.of(
                "internalId", AIActionParamSchema.builder().visibility("internal").build(),
                "secretToken", AIActionParamSchema.builder().visibility("SECRET").build(),
                "systemTrace", AIActionParamSchema.builder().visibility("system").build(),
                "ownedResource", AIActionParamSchema.builder().askUser(false).build(),
                "publicQuery", AIActionParamSchema.builder().description("Visible user query").build()
            ))
            .build();

        assertThat(ActionParameterSupport.isHiddenActionParameter(meta, "shopperSessionId")).isTrue();
        assertThat(ActionParameterSupport.isHiddenActionParameter(meta, "confirmationAccepted")).isTrue();
        assertThat(ActionParameterSupport.isHiddenActionParameter(meta, "internalId")).isTrue();
        assertThat(ActionParameterSupport.isHiddenActionParameter(meta, "secretToken")).isTrue();
        assertThat(ActionParameterSupport.isHiddenActionParameter(meta, "systemTrace")).isTrue();
        assertThat(ActionParameterSupport.isHiddenActionParameter(meta, "ownedResource")).isTrue();
        assertThat(ActionParameterSupport.isUserVisibleActionParameter(meta, "publicQuery")).isTrue();
    }

    @Test
    void shouldTreatDescriptionsParamNamesAndInstructionTextAsPlaceholderValues() {
        AIActionMetaData meta = AIActionMetaData.builder()
            .name("change_delivery_address")
            .parameters(Map.of("shippingAddress", "New shipping address (required)"))
            .build();

        assertThat(ActionParameterSupport.isPlaceholderOrInstructionEcho(
            "shippingAddress",
            "New shipping address (required)",
            meta,
            "change my address"
        )).isTrue();
        assertThat(ActionParameterSupport.isPlaceholderOrInstructionEcho(
            "shippingAddress",
            "shippingAddress",
            meta,
            "change my address"
        )).isTrue();
        assertThat(ActionParameterSupport.isPlaceholderOrInstructionEcho(
            "shippingAddress",
            "Example: 10 High Street",
            meta,
            "change my address"
        )).isTrue();
    }

    @Test
    void shouldTreatWholeInstructionQueryAsPlaceholderOnlyWhenItLooksLikeAnInstruction() {
        AIActionMetaData meta = AIActionMetaData.builder()
            .name("confirmable_echo")
            .parameters(Map.of("message", "Text to echo back"))
            .build();

        assertThat(ActionParameterSupport.isPlaceholderOrInstructionEcho(
            "message",
            "Use action 'confirmable_echo'.",
            meta,
            "Use action 'confirmable_echo'."
        )).isTrue();

        assertThat(ActionParameterSupport.isPlaceholderOrInstructionEcho(
            "shippingAddress",
            "16 dairy drive B2",
            AIActionMetaData.builder()
                .name("change_delivery_address")
                .parameters(Map.of("shippingAddress", "New shipping address"))
                .build(),
            "16 dairy drive B2"
        )).isFalse();
    }
}
