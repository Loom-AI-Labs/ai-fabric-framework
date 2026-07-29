package ai.fabric.execution.input;

import ai.fabric.execution.specialist.manifest.SpecialistSchemaId;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Public, immutable response contract for one pending specialist input request.
 *
 * <p>The schema uses only standard Java JSON values so host applications can
 * serialize it without depending on AI Fabric's internal Jackson generation.</p>
 */
public record SpecialistInputResponseContract(
    SpecialistSchemaId schemaId,
    Map<String, Object> schema
) {
    public SpecialistInputResponseContract {
        Objects.requireNonNull(schemaId, "schemaId is required");
        Objects.requireNonNull(schema, "schema is required");
        schema = immutableObject(schema);
    }

    public SpecialistInputResponseContract(
        SpecialistSchemaId schemaId,
        JsonNode schema
    ) {
        this(schemaId, objectSchema(schema));
    }

    private static Map<String, Object> objectSchema(JsonNode schema) {
        Objects.requireNonNull(schema, "schema is required");
        if (!schema.isObject()) {
            throw new IllegalArgumentException(
                "response schema root must be an object"
            );
        }
        Map<String, Object> values = new LinkedHashMap<>();
        schema.properties().forEach(entry ->
            values.put(entry.getKey(), jsonValue(entry.getValue()))
        );
        return values;
    }

    private static Map<String, Object> immutableObject(
        Map<String, ?> source
    ) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException(
                    "response schema keys must not be blank"
                );
            }
            copy.put(key, immutableValue(value));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value == null
            || value instanceof String
            || value instanceof Boolean
            || value instanceof Byte
            || value instanceof Short
            || value instanceof Integer
            || value instanceof Long
            || value instanceof BigInteger
            || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException(
                    "response schema numbers must be finite"
                );
            }
            return number;
        }
        if (value instanceof Float number) {
            if (!Float.isFinite(number)) {
                throw new IllegalArgumentException(
                    "response schema numbers must be finite"
                );
            }
            return number;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> object = new LinkedHashMap<>();
            map.forEach((key, child) -> {
                if (!(key instanceof String text) || text.isBlank()) {
                    throw new IllegalArgumentException(
                        "response schema keys must be non-blank strings"
                    );
                }
                object.put(text, immutableValue(child));
            });
            return Collections.unmodifiableMap(object);
        }
        if (value instanceof List<?> list) {
            List<Object> array = new ArrayList<>(list.size());
            list.forEach(child -> array.add(immutableValue(child)));
            return Collections.unmodifiableList(array);
        }
        throw new IllegalArgumentException(
            "response schema contains a non-JSON value"
        );
    }

    private static Object jsonValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isObject()) {
            Map<String, Object> object = new LinkedHashMap<>();
            value.properties().forEach(entry ->
                object.put(entry.getKey(), jsonValue(entry.getValue()))
            );
            return Collections.unmodifiableMap(object);
        }
        if (value.isArray()) {
            List<Object> array = new ArrayList<>(value.size());
            value.forEach(child -> array.add(jsonValue(child)));
            return Collections.unmodifiableList(array);
        }
        if (value.isTextual()) {
            return value.textValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isIntegralNumber()) {
            if (value.canConvertToInt()) {
                return value.intValue();
            }
            if (value.canConvertToLong()) {
                return value.longValue();
            }
            return value.bigIntegerValue();
        }
        if (value.isFloatingPointNumber()) {
            return value.decimalValue();
        }
        throw new IllegalArgumentException(
            "response schema contains an unsupported JSON value"
        );
    }
}
