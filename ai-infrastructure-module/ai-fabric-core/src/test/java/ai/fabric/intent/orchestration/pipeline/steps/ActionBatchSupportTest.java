package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.targets.ResolvedTarget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActionBatchSupportTest {

    @Test
    void shouldDetectBatchParamSpecOnlyForArrayBatchTargetsWithItems() {
        AIActionParamSchema item = AIActionParamSchema.builder()
            .type(AIActionParamType.OBJECT)
            .properties(Map.of("sku", stringSchema("sku")))
            .build();
        AIActionParamSchema batch = AIActionParamSchema.builder()
            .name("items")
            .type(AIActionParamType.ARRAY)
            .batchTargets(true)
            .items(item)
            .build();
        AIActionMetaData meta = AIActionMetaData.builder()
            .parameterSchemas(Map.of(
                "ignored", AIActionParamSchema.builder().type(AIActionParamType.ARRAY).batchTargets(true).build(),
                "items", batch
            ))
            .build();

        ActionBatchSupport.BatchParamSpec spec = ActionBatchSupport.findBatchParamSpec(meta);

        assertThat(spec).isNotNull();
        assertThat(spec.paramName()).isEqualTo("items");
        assertThat(spec.schema()).isSameAs(batch);
    }

    @Test
    void shouldNormalizeBatchItemUsingEvidenceKeysDefaultsAndSchemaTypes() {
        AIActionParamSchema variantId = AIActionParamSchema.builder()
            .name("product_variant_id")
            .type(AIActionParamType.STRING)
            .pattern("^commerce://resource/ProductVariant/[0-9]+$")
            .evidenceKeys(List.of("firstAvailableVariantId"))
            .build();
        AIActionParamSchema quantity = AIActionParamSchema.builder()
            .name("quantity")
            .type(AIActionParamType.INTEGER)
            .min(1L)
            .defaultValue(1)
            .build();
        AIActionParamSchema item = AIActionParamSchema.builder()
            .type(AIActionParamType.OBJECT)
            .properties(Map.of("product_variant_id", variantId, "quantity", quantity))
            .requiredProperties(List.of("product_variant_id", "quantity"))
            .build();

        Map<String, Object> normalized = ActionBatchSupport.normalizeBatchItemAgainstSchema(
            Map.of("firstAvailableVariantId", " commerce://resource/ProductVariant/42 "),
            item
        );

        assertThat(normalized)
            .containsEntry("product_variant_id", "commerce://resource/ProductVariant/42")
            .containsEntry("quantity", 1L);
    }

    @Test
    void shouldRejectInvalidBatchItemWhenRequiredSchemaConstraintsFail() {
        AIActionParamSchema item = AIActionParamSchema.builder()
            .type(AIActionParamType.OBJECT)
            .properties(Map.of(
                "sku", AIActionParamSchema.builder()
                    .name("sku")
                    .type(AIActionParamType.STRING)
                    .pattern("^SKU-[0-9]+$")
                    .build()
            ))
            .requiredProperties(List.of("sku"))
            .build();

        Map<String, Object> normalized = ActionBatchSupport.normalizeBatchItemAgainstSchema(
            Map.of("sku", "not-a-sku"),
            item
        );

        assertThat(normalized).isNull();
    }

    @Test
    void shouldDefaultBatchTargetsFromResolvedTargetMetadataAndDeduplicateExistingItems() {
        AIActionMetaData meta = addToCartMeta();
        ActionBatchSupport support = new ActionBatchSupport(mock(AIActionRegistry.class));
        PipelineContext context = PipelineContext.from("add these", OrchestrationContext.forUser("user"))
            .toBuilder()
            .resolvedTargets(List.of(
                target("p1", Map.of("sku", "SKU-1")),
                target("p2", Map.of("sku", "SKU-2"))
            ))
            .build();

        Map<String, Object> updated = support.applyBatchTargetsDefaulting(
            meta,
            Map.of("items", List.of(Map.of("sku", "SKU-1", "quantity", 1))),
            context
        );

        assertThat(updated.get("items")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) updated.get("items");
        assertThat(items).hasSize(2);
        assertThat(items).anySatisfy(item -> assertThat(item).containsEntry("sku", "SKU-1"));
        assertThat(items).anySatisfy(item -> assertThat(item).containsEntry("sku", "SKU-2"));
    }

    @Test
    void shouldPreserveRawExistingBatchValueWhenResolverCanRecoverIt() {
        AIActionParamSchema item = AIActionParamSchema.builder()
            .type(AIActionParamType.OBJECT)
            .properties(Map.of("sku", stringSchema("sku")))
            .requiredProperties(List.of("sku"))
            .build();
        AIActionParamSchema items = AIActionParamSchema.builder()
            .name("items")
            .type(AIActionParamType.ARRAY)
            .batchTargets(true)
            .resolveFrom(Map.of("source", "READ_ACTION", "actionName", "find_items"))
            .items(item)
            .build();
        AIActionMetaData meta = AIActionMetaData.builder()
            .parameterSchemas(Map.of("items", items))
            .build();
        ActionBatchSupport support = new ActionBatchSupport(mock(AIActionRegistry.class));
        Map<String, Object> params = Map.of("items", List.of(Map.of("sku", "")));
        PipelineContext context = PipelineContext.from("add", OrchestrationContext.forUser("user"))
            .toBuilder()
            .resolvedTargets(List.of(target("target", Map.of("title", "missing required id"))))
            .build();

        Map<String, Object> updated = support.applyBatchTargetsDefaulting(meta, params, context);

        assertThat(updated).isSameAs(params);
    }

    @Test
    void shouldCoalesceDuplicateBatchActionIntentsIntoFirstIntent() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionMetaData meta = addToCartMeta();
        when(registry.findMetadata("add_to_cart")).thenReturn(Optional.of(meta));
        ActionBatchSupport support = new ActionBatchSupport(registry);

        Intent first = Intent.builder()
            .type(IntentType.ACTION)
            .intent("add one")
            .action("add_to_cart")
            .actionParams(Map.of("items", List.of(Map.of("sku", "SKU-1", "quantity", 1))))
            .build();
        Intent second = Intent.builder()
            .type(IntentType.ACTION)
            .intent("add two")
            .action("add_to_cart")
            .actionParams(Map.of("items", List.of(
                Map.of("sku", "SKU-1", "quantity", 1),
                Map.of("sku", "SKU-2", "quantity", 1)
            )))
            .build();
        Intent info = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("tell me about shipping")
            .build();

        MultiIntentResponse coalesced = support.coalesceBatchActionIntents(
            MultiIntentResponse.builder()
                .intents(List.of(first, second, info))
                .metadata(Map.of("trace", "kept"))
                .build()
        );

        assertThat(coalesced.getIntents()).hasSize(2);
        Intent merged = coalesced.getIntents().getFirst();
        assertThat(merged.getAction()).isEqualTo("add_to_cart");
        assertThat(merged.getIntent()).isEqualTo("add one");
        assertThat(coalesced.getIntents().get(1)).isSameAs(info);
        assertThat(coalesced.getMetadata()).containsEntry("trace", "kept");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) merged.getActionParams().get("items");
        assertThat(items).hasSize(2);
        assertThat(items).anySatisfy(item -> assertThat(item).containsEntry("sku", "SKU-1"));
        assertThat(items).anySatisfy(item -> assertThat(item).containsEntry("sku", "SKU-2"));
    }

    private AIActionMetaData addToCartMeta() {
        AIActionParamSchema sku = stringSchema("sku");
        AIActionParamSchema quantity = AIActionParamSchema.builder()
            .name("quantity")
            .type(AIActionParamType.INTEGER)
            .defaultValue(1)
            .build();
        AIActionParamSchema item = AIActionParamSchema.builder()
            .type(AIActionParamType.OBJECT)
            .properties(Map.of("sku", sku, "quantity", quantity))
            .requiredProperties(List.of("sku", "quantity"))
            .build();
        AIActionParamSchema items = AIActionParamSchema.builder()
            .name("items")
            .type(AIActionParamType.ARRAY)
            .batchTargets(true)
            .items(item)
            .build();

        return AIActionMetaData.builder()
            .name("add_to_cart")
            .parameterSchemas(Map.of("items", items))
            .build();
    }

    private AIActionParamSchema stringSchema(String name) {
        return AIActionParamSchema.builder()
            .name(name)
            .type(AIActionParamType.STRING)
            .build();
    }

    private ResolvedTarget target(String id, Map<String, String> metadata) {
        return ResolvedTarget.builder()
            .id(id)
            .vectorSpace("product")
            .metadata(metadata)
            .build();
    }
}
