package ai.fabric.intent.extraction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Closed provider schemas for bounded multi-step intent extraction stages. */
final class MultiStepIntentExtractionSchemaSupport {

    private MultiStepIntentExtractionSchemaSupport() {
    }

    static String classificationSchema(ObjectMapper objectMapper) {
        ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        ObjectNode root = objectSchema(mapper);
        ObjectNode properties = root.putObject("properties");

        ObjectNode intents = properties.putObject("intents");
        intents.put("type", "array");
        intents.put("minItems", 1);
        intents.set("items", classificationIntentSchema(mapper));

        ObjectNode metadata = objectSchema(mapper);
        ObjectNode metadataProperties = metadata.putObject("properties");
        metadataProperties.set("retrievalQueryHint", nullableStringSchema(mapper));
        metadata.putArray("required").add("retrievalQueryHint");
        properties.set("metadata", metadata);

        root.putArray("required").add("intents").add("metadata");
        return write(mapper, root);
    }

    static String actionSelectionSchema(ObjectMapper objectMapper,
                                        Collection<Integer> intentIndexes,
                                        Collection<String> allowedActions) {
        ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        List<Integer> indexes = intentIndexes == null
            ? List.of()
            : intentIndexes.stream().filter(Objects::nonNull).distinct().toList();
        List<String> actions = allowedActions == null
            ? List.of()
            : List.copyOf(new LinkedHashSet<>(
                allowedActions.stream().filter(Objects::nonNull).toList()
            ));
        if (indexes.isEmpty()) {
            throw new IllegalArgumentException("intentIndexes must not be empty");
        }
        if (actions.isEmpty()) {
            throw new IllegalArgumentException("allowedActions must not be empty");
        }

        ObjectNode root = objectSchema(mapper);
        ObjectNode properties = root.putObject("properties");
        ObjectNode mappings = properties.putObject("mappings");
        mappings.put("type", "array");
        mappings.put("minItems", indexes.size());
        mappings.put("maxItems", indexes.size());

        ObjectNode mapping = objectSchema(mapper);
        ObjectNode mappingProperties = mapping.putObject("properties");
        ObjectNode intentIndex = mappingProperties.putObject("intentIndex");
        intentIndex.put("type", "integer");
        ArrayNode indexValues = intentIndex.putArray("enum");
        indexes.forEach(indexValues::add);

        ObjectNode selectedAction = mappingProperties.putObject("selectedAction");
        selectedAction.putArray("type").add("string").add("null");
        ArrayNode actionValues = selectedAction.putArray("enum");
        actions.forEach(actionValues::add);
        actionValues.addNull();

        mapping.putArray("required").add("intentIndex").add("selectedAction");
        mappings.set("items", mapping);
        root.putArray("required").add("mappings");
        return write(mapper, root);
    }

    private static ObjectNode classificationIntentSchema(ObjectMapper mapper) {
        ObjectNode intent = objectSchema(mapper);
        ObjectNode properties = intent.putObject("properties");

        properties.set("obligationId", stringSchema(mapper));
        properties.set("type", enumSchema(
            mapper,
            List.of(
                "ACTION",
                "INFORMATION",
                "OUT_OF_SCOPE",
                "CONFIRMATION_POSITIVE",
                "CONFIRMATION_NEGATIVE"
            ),
            false
        ));
        properties.set("intent", nullableStringSchema(mapper));
        properties.set("userGoal", nullableStringSchema(mapper));
        properties.set("actionHint", nullableStringSchema(mapper));

        ObjectNode confidence = properties.putObject("confidence");
        confidence.putArray("type").add("number").add("null");
        confidence.put("minimum", 0.0);
        confidence.put("maximum", 1.0);

        properties.set("requiresRetrieval", booleanSchema(mapper));
        properties.set("requiresGeneration", booleanSchema(mapper));
        properties.set("responseProfile", enumSchema(
            mapper,
            List.of("CONCISE", "STANDARD", "DEEP"),
            true
        ));
        properties.set("requiresTargetResolution", booleanSchema(mapper));
        properties.set("directAnswer", nullableStringSchema(mapper));
        properties.set("generationInstructions", nullableStringSchema(mapper));
        properties.set("needsAdvancedRAG", booleanSchema(mapper));
        properties.set("optimizedQuery", nullableStringSchema(mapper));
        properties.set("vectorSpace", nullableStringSchema(mapper));

        ObjectNode missingInformation = properties.putObject("missingInformation");
        missingInformation.put("type", "array");
        missingInformation.set("items", stringSchema(mapper));
        properties.set("clarificationQuestion", nullableStringSchema(mapper));
        properties.set("uncertaintyReason", nullableStringSchema(mapper));

        ArrayNode required = intent.putArray("required");
        properties.fieldNames().forEachRemaining(required::add);
        return intent;
    }

    private static ObjectNode objectSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode stringSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "string");
        return schema;
    }

    private static ObjectNode nullableStringSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.putArray("type").add("string").add("null");
        return schema;
    }

    private static ObjectNode booleanSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "boolean");
        return schema;
    }

    private static ObjectNode enumSchema(ObjectMapper mapper,
                                         Collection<String> values,
                                         boolean nullable) {
        ObjectNode schema = mapper.createObjectNode();
        if (nullable) {
            schema.putArray("type").add("string").add("null");
        } else {
            schema.put("type", "string");
        }
        ArrayNode allowed = schema.putArray("enum");
        values.forEach(allowed::add);
        if (nullable) {
            allowed.addNull();
        }
        return schema;
    }

    private static String write(ObjectMapper mapper, ObjectNode schema) {
        try {
            return mapper.writeValueAsString(schema);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to build intent extraction JSON schema", ex);
        }
    }
}
