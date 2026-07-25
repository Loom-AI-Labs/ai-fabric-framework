package ai.fabric.indexing.projection;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Array;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Converts projected context values into class-free JSON-compatible values.
 */
final class AIProjectedValueNormalizer {

    private AIProjectedValueNormalizer() {
    }

    static Object normalize(Object value, ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper is required");
        return normalizeValue(value, objectMapper, true);
    }

    private static Object normalizeValue(
        Object value,
        ObjectMapper objectMapper,
        boolean convertPojo
    ) {
        if (value == null
            || value instanceof String
            || value instanceof Number
            || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Character character) {
            return character.toString();
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof UUID
            || value instanceof TemporalAccessor) {
            return value.toString();
        }
        if (value instanceof Date date) {
            return date.toInstant().toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> {
                if (key == null) {
                    throw new IllegalArgumentException(
                        "Projected JSON map keys must not be null"
                    );
                }
                normalized.put(
                    key.toString(),
                    normalizeValue(nestedValue, objectMapper, true)
                );
            });
            return normalized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> normalized = new ArrayList<>();
            iterable.forEach(
                nestedValue -> normalized.add(
                    normalizeValue(nestedValue, objectMapper, true)
                )
            );
            return normalized;
        }
        if (value.getClass().isArray()) {
            List<Object> normalized = new ArrayList<>();
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                normalized.add(
                    normalizeValue(Array.get(value, index), objectMapper, true)
                );
            }
            return normalized;
        }
        if (!convertPojo) {
            throw new IllegalArgumentException(
                "Unsupported projected value type: " + value.getClass().getName()
            );
        }
        try {
            Object converted = objectMapper.convertValue(value, Object.class);
            if (converted == value) {
                throw new IllegalArgumentException(
                    "Unsupported projected value type: "
                        + value.getClass().getName()
                );
            }
            return normalizeValue(converted, objectMapper, false);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Unable to convert projected value to class-free JSON",
                exception
            );
        }
    }
}
