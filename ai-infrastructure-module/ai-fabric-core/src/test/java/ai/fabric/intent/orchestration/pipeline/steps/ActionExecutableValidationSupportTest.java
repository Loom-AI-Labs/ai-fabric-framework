package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionParamType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.fabric.intent.orchestration.pipeline.steps.ActionExecutableValidationSupport.validateExecutableActionParams;
import static org.assertj.core.api.Assertions.assertThat;

class ActionExecutableValidationSupportTest {

    private static final ActionEvidenceSupport.EvidenceBundle EMPTY_EVIDENCE =
        new ActionEvidenceSupport.EvidenceBundle("", "", Map.of(), Map.of());

    @Test
    void shouldSkipNonMcpRuntimeActions() {
        assertThat(validateExecutableActionParams(Map.of("adapterType", "spring-bean"), null, Map.of(), EMPTY_EVIDENCE, Set.of()))
            .isNull();
    }

    @Test
    void shouldDetectMcpRuntimeAndRequiredAnyArgumentsAcrossPathFormats() {
        Map<String, Object> runtimeConfig = Map.of(
            "execution", Map.of(
                "adapterType", "mcp-tool",
                "mcp", Map.of("requiredAnyArguments", List.of("$.params.add_items", "update_items"))
            )
        );

        ActionExecutableValidationSupport.ActionExecutableValidation missing =
            validateExecutableActionParams(runtimeConfig, null, Map.of(), EMPTY_EVIDENCE, Set.of());
        assertThat(missing).isNotNull();
        assertThat(missing.hasFailures()).isTrue();
        assertThat(missing.missingExecutable()).containsExactly("$.params.add_items", "update_items");
        assertThat(missing.publicMissing()).containsExactly("$.params.add_items", "update_items");

        ActionExecutableValidationSupport.ActionExecutableValidation present =
            validateExecutableActionParams(
                runtimeConfig,
                null,
                Map.of("add_items", List.of(Map.of("quantity", 1))),
                EMPTY_EVIDENCE,
                Set.of()
            );
        assertThat(present).isNotNull();
        assertThat(present.hasFailures()).isFalse();
    }

    @Test
    void shouldReportScalarAndRequiredPropertyValidationFailures() {
        Map<String, Object> runtimeConfig = Map.of("adapterType", "mcp-tool");
        AIActionMetaData meta = AIActionMetaData.builder()
            .parameterSchemas(Map.of(
                "quantity", AIActionParamSchema.builder()
                    .name("quantity")
                    .type(AIActionParamType.INTEGER)
                    .min(1L)
                    .max(5L)
                    .build(),
                "coupon", AIActionParamSchema.builder()
                    .name("coupon")
                    .type(AIActionParamType.STRING)
                    .pattern("^[A-Z]{3}$")
                    .build(),
                "mode", AIActionParamSchema.builder()
                    .name("mode")
                    .allowedValues(List.of("pickup"))
                    .build(),
                "item", AIActionParamSchema.builder()
                    .name("item")
                    .type(AIActionParamType.OBJECT)
                    .requiredProperties(List.of("sku"))
                    .properties(Map.of("sku", AIActionParamSchema.builder().name("sku").build()))
                    .build()
            ))
            .build();

        ActionExecutableValidationSupport.ActionExecutableValidation validation =
            validateExecutableActionParams(
                runtimeConfig,
                meta,
                Map.of("quantity", 0, "coupon", "abc", "mode", "delivery", "item", Map.of("quantity", 1)),
                EMPTY_EVIDENCE,
                Set.of()
            );

        assertThat(validation).isNotNull();
        assertThat(validation.invalidArguments())
            .contains("quantity", "coupon", "mode", "item.sku");
        assertThat(validation.untrustedArguments()).isEmpty();
    }

    @Test
    void shouldRejectEvidenceBoundValuesUnlessTrustedByEvidenceOrResolver() {
        Map<String, Object> runtimeConfig = Map.of("adapterType", "mcp-tool");
        AIActionMetaData meta = AIActionMetaData.builder()
            .parameterSchemas(Map.of("add_items", addItemsSchema()))
            .build();
        Map<String, Object> params = Map.of("add_items", List.of(Map.of(
            "product_variant_id", "commerce://resource/ProductVariant/1",
            "quantity", 1
        )));

        ActionExecutableValidationSupport.ActionExecutableValidation untrusted =
            validateExecutableActionParams(runtimeConfig, meta, params, EMPTY_EVIDENCE, Set.of());
        assertThat(untrusted).isNotNull();
        assertThat(untrusted.untrustedArguments()).containsExactly("add_items[0].product_variant_id");

        ActionEvidenceSupport.EvidenceBundle trustedEvidence = new ActionEvidenceSupport.EvidenceBundle(
            "",
            "",
            Map.of("product_variant_id", Set.of("commerce://resource/productvariant/1")),
            Map.of("pinned", true)
        );
        ActionExecutableValidationSupport.ActionExecutableValidation trustedByEvidence =
            validateExecutableActionParams(runtimeConfig, meta, params, trustedEvidence, Set.of());
        assertThat(trustedByEvidence).isNotNull();
        assertThat(trustedByEvidence.hasFailures()).isFalse();

        ActionExecutableValidationSupport.ActionExecutableValidation trustedByResolver =
            validateExecutableActionParams(runtimeConfig, meta, params, EMPTY_EVIDENCE, Set.of("add_items"));
        assertThat(trustedByResolver).isNotNull();
        assertThat(trustedByResolver.hasFailures()).isFalse();
    }

    private AIActionParamSchema addItemsSchema() {
        return AIActionParamSchema.builder()
            .name("add_items")
            .type(AIActionParamType.ARRAY)
            .items(AIActionParamSchema.builder()
                .name("item")
                .type(AIActionParamType.OBJECT)
                .requiredProperties(List.of("product_variant_id", "quantity"))
                .properties(Map.of(
                    "product_variant_id", AIActionParamSchema.builder()
                        .name("product_variant_id")
                        .type(AIActionParamType.STRING)
                        .evidenceBound(true)
                        .evidenceKeys(List.of("product_variant_id"))
                        .build(),
                    "quantity", AIActionParamSchema.builder()
                        .name("quantity")
                        .type(AIActionParamType.INTEGER)
                        .min(1L)
                        .build()
                ))
                .build())
            .build();
    }
}
