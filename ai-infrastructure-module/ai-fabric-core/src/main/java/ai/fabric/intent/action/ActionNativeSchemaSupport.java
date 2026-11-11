package ai.fabric.intent.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.networknt.schema.Schema;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.dialect.BasicDialectRegistry;
import com.networknt.schema.dialect.Dialects;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Native JSON Schema contracts; validation never resolves external resources. */
public final class ActionNativeSchemaSupport {
    public static final String INPUT_SCHEMA = "inputSchema";
    public static final String OUTPUT_SCHEMA = "outputSchema";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DRAFT_2020_12 = SpecificationVersion.DRAFT_2020_12.getDialectId();
    private static final String DRAFT_7 = SpecificationVersion.DRAFT_7.getDialectId();
    private static final Map<String, String> QUALIFIED_DIALECTS = Map.of(
        DRAFT_2020_12, DRAFT_2020_12,
        DRAFT_2020_12 + "#", DRAFT_2020_12,
        DRAFT_7, DRAFT_7,
        "http://json-schema.org/draft-07/schema", DRAFT_7,
        "https://json-schema.org/draft-07/schema#", DRAFT_7,
        "https://json-schema.org/draft-07/schema", DRAFT_7
    );
    private static final Set<String> BUNDLED_SCHEMA_RESOURCES = Set.of(
        DRAFT_2020_12, DRAFT_7, "http://json-schema.org/draft-07/schema",
        "https://json-schema.org/draft/2020-12/meta/core",
        "https://json-schema.org/draft/2020-12/meta/applicator",
        "https://json-schema.org/draft/2020-12/meta/unevaluated",
        "https://json-schema.org/draft/2020-12/meta/validation",
        "https://json-schema.org/draft/2020-12/meta/meta-data",
        "https://json-schema.org/draft/2020-12/meta/format-annotation",
        "https://json-schema.org/draft/2020-12/meta/format-assertion",
        "https://json-schema.org/draft/2020-12/meta/content"
    );
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(
        SpecificationVersion.DRAFT_2020_12,
        builder -> builder
            .dialectRegistry(new BasicDialectRegistry(List.of(Dialects.getDraft202012(), Dialects.getDraft7())))
            .schemaLoader(loader -> loader.fetchRemoteResources(false)
                .resourceLoaders(loaders -> loaders.values(List::clear))
                .allow(iri -> BUNDLED_SCHEMA_RESOURCES.contains(iri.toString())))
    );
    private static final Cache<String, Schema> SCHEMAS = Caffeine.newBuilder().maximumSize(256).build();
    private static final Set<String> REFERENCES = Set.of("$ref", "$dynamicRef", "$recursiveRef");
    private static final Set<String> SCHEMA_MAPS = Set.of(
        "$defs", "definitions", "properties", "patternProperties", "dependentSchemas", "dependencies"
    );
    private static final Set<String> SCHEMA_VALUES = Set.of(
        "items", "contains", "additionalProperties", "unevaluatedProperties", "additionalItems",
        "propertyNames", "not", "if", "then", "else", "contentSchema", "unevaluatedItems"
    );
    private static final Set<String> SCHEMA_LISTS = Set.of("allOf", "anyOf", "oneOf", "prefixItems");
    private static final Set<String> SAME_INSTANCE_MAPS = Set.of("dependentSchemas");
    private static final Set<String> SAME_INSTANCE_SCHEMAS = Set.of("if", "then", "else", "not");
    private static final Set<String> SAME_INSTANCE_LISTS = Set.of("allOf", "anyOf", "oneOf");

    private ActionNativeSchemaSupport() {
    }

    public static Map<String, Object> snapshot(Map<String, Object> schema) {
        return schema == null ? null : immutableMap(schema);
    }

    /** Presence only; the executable native schema still decides whether null is valid. */
    public static boolean hasExplicitPublicNull(AIActionMetaData metadata, Map<String, Object> params, String name) {
        if (metadata == null || metadata.getInputSchema() == null || params == null
            || !params.containsKey(name) || params.get(name) != null
            || Set.of("shopperSessionId", "confirmationAccepted").contains(name)) {
            return false;
        }
        AIActionParamSchema binding = metadata.getParameterSchemas() == null ? null : metadata.getParameterSchemas().get(name);
        return binding == null || (!Boolean.FALSE.equals(binding.getAskUser()) && !Boolean.TRUE.equals(binding.getEvidenceBound())
            && !Set.of("INTERNAL", "SECRET", "SYSTEM").contains(
                binding.getVisibility() == null ? "" : binding.getVisibility().trim().toUpperCase(java.util.Locale.ROOT)));
    }

    public static void validateDefinition(Map<String, Object> schema) {
        if (schema != null) {
            compiled(schema);
        }
    }

    public static void validateInput(Map<String, Object> schema, Map<String, Object> parameters) {
        if (schema == null) {
            return;
        }
        var errors = compiled(schema).validate(
            JSON.valueToTree(parameters == null ? Map.of() : parameters).toString(), InputFormat.JSON);
        if (!errors.isEmpty()) {
            String location = errors.getFirst().getInstanceLocation().toString();
            // This is the receiver's executable input contract, not a semantic-quality gate.
            throw new ActionParameterContractValidator.ActionParameterContractException(
                "ACTION_PARAMETER_INVALID", "Action parameters do not satisfy the native input schema at " + location + ".", location
            );
        }
    }

    public static Map<String, Object> publicInputSchema(AIActionMetaData metadata) {
        if (metadata == null || metadata.getInputSchema() == null) {
            return null;
        }
        Set<String> hidden = new java.util.LinkedHashSet<>(Set.of("shopperSessionId", "confirmationAccepted"));
        if (metadata.getParameterSchemas() != null) {
            metadata.getParameterSchemas().forEach((name, schema) -> {
                if (schema != null && (Boolean.FALSE.equals(schema.getAskUser())
                    || Set.of("INTERNAL", "SECRET", "SYSTEM").contains(
                        schema.getVisibility() == null ? "" : schema.getVisibility().trim().toUpperCase(java.util.Locale.ROOT)))) {
                    hidden.add(name);
                }
            });
        }
        return immutableMap(project(metadata.getInputSchema(), hidden));
    }

    public static String json(Map<String, Object> schema) {
        try {
            return JSON.writeValueAsString(schema);
        } catch (Exception ex) {
            throw invalidDefinition();
        }
    }

    private static Schema compiled(Map<String, Object> definition) {
        return SCHEMAS.get(json(definition), ignored -> {
            JsonNode node = JSON.valueToTree(definition);
            qualifySchemaResources(node);
            try {
                String dialect = node.has("$schema") ? node.get("$schema").textValue() : DRAFT_2020_12;
                // JSON is the boundary between the framework's Jackson 2 DTOs and the validator's Jackson 3 tree.
                String schemaJson = node.toString();
                if (!REGISTRY.getSchema(SchemaLocation.of(dialect)).validate(schemaJson, InputFormat.JSON).isEmpty()) {
                    throw invalidDefinition();
                }
                Schema schema = REGISTRY.getSchema(schemaJson, InputFormat.JSON);
                schema.initializeValidators();
                return schema;
            } catch (RuntimeException ex) {
                throw invalidDefinition();
            }
        });
    }

    private static void qualifySchemaResources(JsonNode node) {
        if (!node.isObject()) {
            return;
        }
        JsonNode declaredDialect = node.get("$schema");
        if (declaredDialect != null) {
            String qualified = declaredDialect.isTextual() ? QUALIFIED_DIALECTS.get(declaredDialect.textValue()) : null;
            if (qualified == null) {
                throw new IllegalArgumentException("Action schemas support only qualified Draft 2020-12 and Draft 07 dialects.");
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("$schema", qualified);
        }
        REFERENCES.forEach(key -> {
            JsonNode reference = node.get(key);
            if (reference != null && (!reference.isTextual() || !isLocalReference(reference.textValue()))) {
                throw new IllegalArgumentException("Action schemas may use only local fragment references.");
            }
        });
        SCHEMA_MAPS.forEach(key -> {
            JsonNode values = node.get(key);
            if (values != null && values.isObject()) {
                values.elements().forEachRemaining(ActionNativeSchemaSupport::qualifySchemaResources);
            }
        });
        SCHEMA_VALUES.forEach(key -> {
            JsonNode value = node.get(key);
            if (value != null) {
                if (value.isArray()) {
                    value.elements().forEachRemaining(ActionNativeSchemaSupport::qualifySchemaResources);
                } else {
                    qualifySchemaResources(value);
                }
            }
        });
        SCHEMA_LISTS.forEach(key -> {
            JsonNode values = node.get(key);
            if (values != null && values.isArray()) {
                values.elements().forEachRemaining(ActionNativeSchemaSupport::qualifySchemaResources);
            }
        });
    }

    private static boolean isLocalReference(String value) {
        try {
            URI reference = URI.create(value);
            return reference.getScheme() == null && reference.getRawAuthority() == null
                && (reference.getRawPath() == null || reference.getRawPath().isEmpty())
                && reference.getRawQuery() == null && reference.getRawFragment() != null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static IllegalArgumentException invalidDefinition() {
        return new IllegalArgumentException("Action native JSON Schema is invalid or uses an unsupported schema resource.");
    }

    private static Map<String, Object> project(Map<String, Object> schema, Set<String> hidden) {
        Map<String, Object> out = new LinkedHashMap<>(schema);
        if (schema.get("properties") instanceof Map<?, ?> properties) {
            Map<String, Object> visible = new LinkedHashMap<>();
            properties.forEach((key, value) -> {
                if (!hidden.contains(key.toString())) {
                    visible.put(key.toString(), value);
                }
            });
            out.put("properties", visible);
        }
        if (schema.get("required") instanceof List<?> required) {
            out.put("required", required.stream().filter(name -> !hidden.contains(name.toString())).toList());
        }
        SAME_INSTANCE_MAPS.forEach(key -> {
            if (schema.get(key) instanceof Map<?, ?> definitions) {
                Map<String, Object> projected = new LinkedHashMap<>();
                definitions.forEach((name, value) -> projected.put(name.toString(), projectValue(value, hidden)));
                out.put(key, projected);
            }
        });
        SAME_INSTANCE_SCHEMAS.forEach(key -> {
            if (schema.containsKey(key)) {
                out.put(key, projectValue(schema.get(key), hidden));
            }
        });
        SAME_INSTANCE_LISTS.forEach(key -> {
            if (schema.get(key) instanceof List<?> values) {
                out.put(key, values.stream().map(value -> projectValue(value, hidden)).toList());
            }
        });
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object projectValue(Object value, Set<String> hidden) {
        return value instanceof Map<?, ?> map ? project((Map<String, Object>) map, hidden) : value;
    }

    private static Map<String, Object> immutableMap(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(key.toString(), immutable(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            return immutableMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(immutable(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
