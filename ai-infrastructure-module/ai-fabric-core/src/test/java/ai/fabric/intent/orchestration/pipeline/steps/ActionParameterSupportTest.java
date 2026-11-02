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

}
