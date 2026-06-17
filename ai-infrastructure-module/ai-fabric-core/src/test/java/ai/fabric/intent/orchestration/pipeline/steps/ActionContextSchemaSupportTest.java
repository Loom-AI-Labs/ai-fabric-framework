package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionParamType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ActionContextSchemaSupportTest {

    @Test
    void shouldParseNumericValuesAndValidatePrimitiveSchemas() {
        assertThat(ActionContextSchemaSupport.parseLong(" 42 ")).isEqualTo(42L);
        assertThat(ActionContextSchemaSupport.parseLong("x")).isNull();
        assertThat(ActionContextSchemaSupport.parseDouble(" 3.5 ")).isEqualTo(3.5d);
        assertThat(ActionContextSchemaSupport.parseDouble("x")).isNull();

        assertThat(ActionContextSchemaSupport.actionParamValueSatisfiesSchema(
            "quantity",
            "4",
            AIActionParamSchema.builder().type(AIActionParamType.INTEGER).min(1L).max(5L).build()
        )).isTrue();
        assertThat(ActionContextSchemaSupport.actionParamValueSatisfiesSchema(
            "quantity",
            "9",
            AIActionParamSchema.builder().type(AIActionParamType.INTEGER).min(1L).max(5L).build()
        )).isFalse();
        assertThat(ActionContextSchemaSupport.actionParamValueSatisfiesSchema(
            "mode",
            "EXPRESS",
            AIActionParamSchema.builder().type(AIActionParamType.STRING).allowedValues(List.of("standard", "express")).build()
        )).isTrue();
        assertThat(ActionContextSchemaSupport.actionParamValueSatisfiesSchema(
            "code",
            "ABC-123",
            AIActionParamSchema.builder().type(AIActionParamType.STRING).pattern("[A-Z]+-\\d+").build()
        )).isTrue();
    }

    @Test
    void shouldValidateObjectAndArraySchemas() {
        AIActionParamSchema itemSchema = AIActionParamSchema.builder()
            .type(AIActionParamType.OBJECT)
            .properties(Map.of(
                "id", AIActionParamSchema.builder().type(AIActionParamType.STRING).required(true).build(),
                "quantity", AIActionParamSchema.builder().type(AIActionParamType.INTEGER).build()
            ))
            .requiredProperties(List.of("id"))
            .build();
        AIActionParamSchema arraySchema = AIActionParamSchema.builder()
            .type(AIActionParamType.ARRAY)
            .items(itemSchema)
            .build();

        assertThat(ActionContextSchemaSupport.actionParamValueSatisfiesSchema(
            "items",
            List.of(Map.of("id", "sku-1", "quantity", "2")),
            arraySchema
        )).isTrue();
        assertThat(ActionContextSchemaSupport.actionParamValueSatisfiesSchema(
            "items",
            List.of(Map.of("quantity", "2")),
            arraySchema
        )).isFalse();
    }

    @Test
    void shouldDecideWhenConfiguredParamsNeedResolution() {
        AIActionParamSchema schema = AIActionParamSchema.builder()
            .type(AIActionParamType.INTEGER)
            .resolveFrom(Map.of("source", "RUNTIME_CONTEXT"))
            .build();

        assertThat(ActionContextSchemaSupport.shouldResolveConfiguredActionParam("quantity", schema, null)).isTrue();
        assertThat(ActionContextSchemaSupport.shouldResolveConfiguredActionParam("quantity", schema, "not-int")).isTrue();
        assertThat(ActionContextSchemaSupport.shouldResolveConfiguredActionParam("quantity", schema, "3")).isFalse();
        assertThat(ActionContextSchemaSupport.shouldResolveConfiguredActionParam("quantity", AIActionParamSchema.builder().build(), null))
            .isFalse();
    }

    @Test
    void shouldNormalizeResolvedValuesAgainstSchema() {
        assertThat(ActionContextSchemaSupport.normalizeResolvedActionParamValue(" value ")).isEqualTo("value");

        AIActionParamSchema itemSchema = AIActionParamSchema.builder()
            .type(AIActionParamType.OBJECT)
            .properties(Map.of("id", AIActionParamSchema.builder().type(AIActionParamType.STRING).build()))
            .build();
        AIActionParamSchema arraySchema = AIActionParamSchema.builder()
            .type(AIActionParamType.ARRAY)
            .items(itemSchema)
            .build();

        Object normalized = ActionContextSchemaSupport.normalizeResolvedActionParamValue(
            Map.of("id", "sku-1"),
            arraySchema
        );

        assertThat(normalized).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> normalizedItems = (List<Map<String, Object>>) normalized;
        assertThat(normalizedItems).containsExactly(Map.of("id", "sku-1"));
        assertThat(ActionContextSchemaSupport.hasMeaningfulActionParamValue(List.of())).isFalse();
        assertThat(ActionContextSchemaSupport.hasMeaningfulActionParamValue("x")).isTrue();
    }
}
