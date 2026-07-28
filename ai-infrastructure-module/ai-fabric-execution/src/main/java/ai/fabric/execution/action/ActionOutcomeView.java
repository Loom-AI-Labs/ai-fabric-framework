package ai.fabric.execution.action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Application-owned safe projection of an authoritative action result.
 */
public record ActionOutcomeView(
    String actionName,
    String message,
    Map<String, Object> data
) {
    private static final int MAX_DATA_FIELDS = 32;
    private static final int MAX_NESTED_FIELDS = 64;
    private static final int MAX_ARRAY_ITEMS = 100;
    private static final int MAX_STRING_LENGTH = 4096;
    private static final int MAX_DEPTH = 8;

    public ActionOutcomeView {
        actionName = requireText(actionName, "actionName", 160);
        message = requireText(message, "message", 1000);
        data = data == null || data.isEmpty()
            ? Map.of()
            : freezeMap(data, 0, MAX_DATA_FIELDS);
    }

    private static Map<String, Object> freezeMap(
        Map<?, ?> source,
        int depth,
        int maxFields
    ) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(
                "Action outcome exceeds the maximum nesting depth"
            );
        }
        if (source.size() > maxFields) {
            throw new IllegalArgumentException(
                "Action outcome contains too many fields"
            );
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException(
                    "Action outcome keys must be non-blank strings"
                );
            }
            copy.put(name, freeze(value, depth + 1));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object freeze(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(
                "Action outcome exceeds the maximum nesting depth"
            );
        }
        if (value == null || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String text) {
            if (text.length() > MAX_STRING_LENGTH) {
                throw new IllegalArgumentException(
                    "Action outcome text exceeds the maximum length"
                );
            }
            return text;
        }
        if (value instanceof Number number) {
            if (number instanceof Double item && !Double.isFinite(item)) {
                throw new IllegalArgumentException(
                    "Action outcome numbers must be finite"
                );
            }
            if (number instanceof Float item && !Float.isFinite(item)) {
                throw new IllegalArgumentException(
                    "Action outcome numbers must be finite"
                );
            }
            return number;
        }
        if (value instanceof Map<?, ?> map) {
            return freezeMap(map, depth, MAX_NESTED_FIELDS);
        }
        if (value instanceof List<?> list) {
            if (list.size() > MAX_ARRAY_ITEMS) {
                throw new IllegalArgumentException(
                    "Action outcome array contains too many items"
                );
            }
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(freeze(item, depth + 1));
            }
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException(
            "Action outcome values must be JSON-safe scalars, maps, or lists"
        );
    }

    private static String requireText(
        String value,
        String field,
        int maxLength
    ) {
        String normalized = Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                field + " must not exceed " + maxLength + " characters"
            );
        }
        return normalized;
    }
}
