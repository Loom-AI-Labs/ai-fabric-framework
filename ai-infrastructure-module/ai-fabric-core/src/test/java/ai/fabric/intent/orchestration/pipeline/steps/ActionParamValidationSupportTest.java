package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.OrchestrationProperties.ActionParamProvenanceMode;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.fabric.intent.orchestration.pipeline.steps.ActionParamValidationSupport.actionExecutableValidationMessage;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionParamValidationSupport.mergeExecutableValidation;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionParamValidationSupport.publicActionParamValidationMetadata;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionParamValidationSupport.suppressConfirmationGateParameter;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionParamValidationSupport.validateRequiredActionParams;
import static org.assertj.core.api.Assertions.assertThat;

class ActionParamValidationSupportTest {

    @Test
    void shouldWarnOnUserVisibleProvenanceByDefaultAndBlockHiddenParams() {
        AIActionMetaData meta = AIActionMetaData.builder()
            .name("change_delivery")
            .parameters(Map.of("shippingAddress", "New shipping address (required)"))
            .parameterSchemas(Map.of("cartId", AIActionParamSchema.builder().name("cartId").askUser(false).build()))
            .requiredParameters(Set.of("shippingAddress", "productId", "cartId"))
            .build();

        PipelineContext context = PipelineContext.from(
            "Please change my address",
            OrchestrationContext.forUser("user")
        );

        ActionParamValidationSupport.ActionParamValidation validation = validateRequiredActionParams(
            meta,
            Map.of(
                "shippingAddress", "New shipping address (required)",
                "productId", "PRODUCT-1",
                "cartId", "CART-1"
            ),
            context
        );

        assertThat(validation).isNotNull();
        assertThat(validation.missingRequired()).containsExactlyInAnyOrder("shippingAddress", "cartId");
        assertThat(validation.provenanceMissing()).containsExactly("productId");
        assertThat(validation.debugMetadata())
            .containsEntry("provenanceMode", "WARN")
            .containsEntry("provenanceBlocking", false)
            .containsEntry("untrustedHiddenParameters", List.of("cartId"))
            .containsKeys("sourcesUsed", "missing", "hardMissing", "provenanceMissing");
    }

    @Test
    void shouldBlockUserVisibleProvenanceWhenConfiguredStrict() {
        AIActionMetaData meta = AIActionMetaData.builder()
            .name("change_delivery")
            .requiredParameters(Set.of("productId"))
            .build();
        PipelineContext context = PipelineContext.from(
            "Please change my address",
            OrchestrationContext.forUser("user")
        );

        ActionParamValidationSupport.ActionParamValidation validation = validateRequiredActionParams(
            meta,
            Map.of("productId", "PRODUCT-1"),
            context,
            Set.of(),
            ActionParamProvenanceMode.BLOCK
        );

        assertThat(validation).isNotNull();
        assertThat(validation.missingRequired()).containsExactly("productId");
        assertThat(validation.provenanceMissing()).containsExactly("productId");
        assertThat(validation.debugMetadata())
            .containsEntry("provenanceMode", "BLOCK")
            .containsEntry("provenanceBlocking", true);
    }

    @Test
    void shouldSkipUserVisibleProvenanceWhenConfiguredOff() {
        AIActionMetaData meta = AIActionMetaData.builder()
            .name("change_delivery")
            .requiredParameters(Set.of("productId"))
            .build();
        PipelineContext context = PipelineContext.from(
            "Please change my address",
            OrchestrationContext.forUser("user")
        );

        ActionParamValidationSupport.ActionParamValidation validation = validateRequiredActionParams(
            meta,
            Map.of("productId", "PRODUCT-1"),
            context,
            Set.of(),
            ActionParamProvenanceMode.OFF
        );

        assertThat(validation).isNotNull();
        assertThat(validation.missingRequired()).isEmpty();
        assertThat(validation.provenanceMissing()).isEmpty();
        assertThat(validation.debugMetadata())
            .containsEntry("provenanceMode", "OFF")
            .containsEntry("provenanceBlocking", false);
    }

    @Test
    void shouldBlockEvidenceBoundParamsEvenWhenProvenanceModeWarn() {
        AIActionMetaData meta = AIActionMetaData.builder()
            .name("update_resource")
            .parameterSchemas(Map.of(
                "resourceId", AIActionParamSchema.builder()
                    .name("resourceId")
                    .evidenceBound(true)
                    .evidenceKeys(List.of("resourceId"))
                    .build()
            ))
            .requiredParameters(Set.of("resourceId"))
            .build();
        PipelineContext context = PipelineContext.from("update it", OrchestrationContext.forUser("user"));

        ActionParamValidationSupport.ActionParamValidation validation = validateRequiredActionParams(
            meta,
            Map.of("resourceId", "RESOURCE-1"),
            context,
            Set.of(),
            ActionParamProvenanceMode.WARN
        );

        assertThat(validation).isNotNull();
        assertThat(validation.missingRequired()).containsExactly("resourceId");
        assertThat(validation.provenanceMissing()).isEmpty();
        assertThat(validation.debugMetadata())
            .containsEntry("untrustedEvidenceBoundParameters", List.of("resourceId"))
            .containsEntry("provenanceMode", "WARN");
    }

    @Test
    void shouldTrustResolvedParametersWithoutUserOrPinnedProvenance() {
        AIActionMetaData meta = AIActionMetaData.builder()
            .requiredParameters(Set.of("productId"))
            .build();
        PipelineContext context = PipelineContext.from("add it", OrchestrationContext.forUser("user"));

        ActionParamValidationSupport.ActionParamValidation validation = validateRequiredActionParams(
            meta,
            Map.of("productId", "PRODUCT-1"),
            context,
            Set.of("productId")
        );

        assertThat(validation).isNotNull();
        assertThat(validation.missingRequired()).isEmpty();
        assertThat(validation.provenanceMissing()).isEmpty();
    }

    @Test
    void shouldSuppressConfirmationGateParameterOnlyForConfirmableActions() {
        ActionParamValidationSupport.ActionParamValidation validation =
            new ActionParamValidationSupport.ActionParamValidation(
                List.of("confirmationAccepted", "sku"),
                List.of("confirmationAccepted"),
                Map.of("missing", List.of("confirmationAccepted", "sku"))
            );

        assertThat(suppressConfirmationGateParameter(validation, false)).isSameAs(validation);

        ActionParamValidationSupport.ActionParamValidation suppressed =
            suppressConfirmationGateParameter(validation, true);

        assertThat(suppressed.missingRequired()).containsExactly("sku");
        assertThat(suppressed.provenanceMissing()).isEmpty();
        assertThat(suppressed.debugMetadata()).containsEntry("confirmationGateHidden", true);
    }

    @Test
    void shouldBuildPublicValidationMetadataAndCountHiddenMissingParameters() {
        AIActionMetaData meta = AIActionMetaData.builder()
            .parameterSchemas(Map.of(
                "cartId", AIActionParamSchema.builder().name("cartId").askUser(false).build(),
                "sku", AIActionParamSchema.builder().name("sku").build()
            ))
            .build();
        ActionParamValidationSupport.ActionParamValidation validation =
            new ActionParamValidationSupport.ActionParamValidation(
                List.of("cartId", "sku"),
                List.of("cartId", "sku"),
                Map.of(
                    "missing", List.of("cartId", "sku"),
                    "provenanceMissing", List.of("cartId", "sku"),
                    "sourcesUsed", Map.of("user", true)
                )
            );

        Map<String, Object> metadata = publicActionParamValidationMetadata(meta, validation);

        assertThat(metadata).containsKey("actionParamValidation");
        @SuppressWarnings("unchecked")
        Map<String, Object> debug = (Map<String, Object>) metadata.get("actionParamValidation");
        assertThat(debug)
            .containsEntry("missing", List.of("sku"))
            .containsEntry("provenanceMissing", List.of("sku"))
            .containsEntry("hiddenContextMissingCount", 1L)
            .containsEntry("sourcesUsed", Map.of("user", true));
    }

    @Test
    void shouldMergeExecutableValidationAndChooseUserMessageByFailureType() {
        ActionParamValidationSupport.ActionParamValidation requiredValidation =
            new ActionParamValidationSupport.ActionParamValidation(List.of("sku"), List.of(), Map.of("missing", List.of("sku")));
        ActionExecutableValidationSupport.ActionExecutableValidation executableValidation =
            new ActionExecutableValidationSupport.ActionExecutableValidation(
                List.of(),
                List.of(),
                List.of("add_items[0].product_variant_id"),
                Map.of("untrustedArguments", List.of("add_items[0].product_variant_id"))
            );

        ActionParamValidationSupport.ActionParamValidation merged =
            mergeExecutableValidation(requiredValidation, executableValidation);

        assertThat(merged.debugMetadata()).containsKey("executableValidation");
        assertThat(actionExecutableValidationMessage(executableValidation)).contains("trusted selected item");
        assertThat(actionExecutableValidationMessage(new ActionExecutableValidationSupport.ActionExecutableValidation(
            List.of(),
            List.of("quantity"),
            List.of(),
            Map.of()
        ))).contains("valid action details");
        assertThat(actionExecutableValidationMessage(new ActionExecutableValidationSupport.ActionExecutableValidation(
            List.of("add_items"),
            List.of(),
            List.of(),
            Map.of()
        ))).contains("specific target");
    }
}
