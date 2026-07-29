package ai.fabric.execution.specialist.client;

import ai.fabric.execution.specialist.manifest.SpecialistSchemaDefinition;
import ai.fabric.llm.structured.springai.SpringAiStructuredOutputSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Verifies that a Java convenience type represents the pinned manifest schema.
 */
final class SpecialistSchemaBindingValidator {

    private final ObjectMapper objectMapper;

    SpecialistSchemaBindingValidator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper is required"
        );
    }

    void validate(
        SpecialistSchemaDefinition manifestSchema,
        Class<?> javaType,
        String direction
    ) {
        Objects.requireNonNull(manifestSchema, "manifestSchema is required");
        Objects.requireNonNull(javaType, "javaType is required");
        String generated = SpringAiStructuredOutputSupport
            .bean(javaType)
            .jsonSchema();
        if (generated == null || generated.isBlank()) {
            throw failure(
                direction,
                "the Java type does not expose a JSON schema"
            );
        }
        try {
            compare(
                manifestSchema.spec().schema(),
                objectMapper.readTree(generated),
                "$",
                direction
            );
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(
                "Cannot bind specialist " + direction
                    + " type because its generated schema is invalid",
                ex
            );
        }
    }

    private void compare(
        JsonNode manifest,
        JsonNode generated,
        String path,
        String direction
    ) {
        String manifestType = scalarType(manifest);
        String generatedType = scalarType(generated);
        if (manifestType != null
            && generatedType != null
            && !manifestType.equals(generatedType)) {
            throw failure(
                direction,
                path + " expects " + manifestType
                    + " but the Java type exposes " + generatedType
            );
        }
        compareEnum(manifest, generated, path, direction);
        if ("object".equals(manifestType)) {
            compareObject(manifest, generated, path, direction);
        } else if ("array".equals(manifestType)) {
            JsonNode manifestItems = manifest.get("items");
            JsonNode generatedItems = generated.get("items");
            if (manifestItems != null && generatedItems != null) {
                compare(
                    manifestItems,
                    generatedItems,
                    path + "[]",
                    direction
                );
            }
        }
    }

    private void compareObject(
        JsonNode manifest,
        JsonNode generated,
        String path,
        String direction
    ) {
        JsonNode manifestProperties = manifest.path("properties");
        JsonNode generatedProperties = generated.path("properties");
        Set<String> manifestNames = fieldNames(manifestProperties);
        Set<String> generatedNames = fieldNames(generatedProperties);
        if (!generatedNames.containsAll(manifestNames)) {
            Set<String> missing = new LinkedHashSet<>(manifestNames);
            missing.removeAll(generatedNames);
            throw failure(
                direction,
                path + " is missing Java properties " + missing
            );
        }
        if (manifest.path("additionalProperties").isBoolean()
            && !manifest.path("additionalProperties").booleanValue()
            && !manifestNames.equals(generatedNames)) {
            Set<String> extra = new LinkedHashSet<>(generatedNames);
            extra.removeAll(manifestNames);
            throw failure(
                direction,
                path + " exposes Java properties not allowed by the manifest "
                    + extra
            );
        }
        Set<String> required = textValues(manifest.path("required"));
        if (!generatedNames.containsAll(required)) {
            Set<String> missing = new LinkedHashSet<>(required);
            missing.removeAll(generatedNames);
            throw failure(
                direction,
                path + " cannot represent required properties " + missing
            );
        }
        for (String name : manifestNames) {
            compare(
                manifestProperties.get(name),
                generatedProperties.get(name),
                path + "." + name,
                direction
            );
        }
    }

    private void compareEnum(
        JsonNode manifest,
        JsonNode generated,
        String path,
        String direction
    ) {
        Set<String> expected = textValues(manifest.path("enum"));
        if (expected.isEmpty()) {
            return;
        }
        Set<String> actual = textValues(generated.path("enum"));
        if (!actual.containsAll(expected)) {
            throw failure(
                direction,
                path + " cannot represent manifest enum values " + expected
            );
        }
    }

    private String scalarType(JsonNode schema) {
        if (schema == null) {
            return null;
        }
        JsonNode type = schema.get("type");
        if (type == null) {
            return null;
        }
        if (type.isTextual()) {
            return type.textValue();
        }
        if (type.isArray()) {
            for (JsonNode candidate : type) {
                if (candidate.isTextual()
                    && !"null".equals(candidate.textValue())) {
                    return candidate.textValue();
                }
            }
        }
        return null;
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> values = new LinkedHashSet<>();
        if (node != null && node.isObject()) {
            node.fieldNames().forEachRemaining(values::add);
        }
        return values;
    }

    private Set<String> textValues(JsonNode node) {
        Set<String> values = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            node.forEach(value -> {
                if (value.isTextual()) {
                    values.add(value.textValue());
                }
            });
        }
        return values;
    }

    private IllegalArgumentException failure(
        String direction,
        String detail
    ) {
        return new IllegalArgumentException(
            "Cannot bind specialist " + direction + " type: " + detail
        );
    }
}
