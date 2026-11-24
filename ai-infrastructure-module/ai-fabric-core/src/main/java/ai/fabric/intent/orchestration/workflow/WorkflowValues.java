package ai.fabric.intent.orchestration.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable JSON values, retaining explicit nulls accepted by native action schemas. */
final class WorkflowValues {
    private WorkflowValues() {}
    static Map<String, Object> snapshot(Map<String, Object> values) {
        if (values == null) return Map.of();
        var copy = new LinkedHashMap<String, Object>();
        values.forEach((key, value) -> copy.put(java.util.Objects.requireNonNull(key), freeze(value)));
        return Collections.unmodifiableMap(copy);
    }
    private static Object freeze(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<String, Object>();
            map.forEach((key, child) -> {
                if (!(key instanceof String text)) throw new IllegalArgumentException("Workflow JSON keys must be strings");
                copy.put(text, freeze(child));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            var copy = new ArrayList<>(); list.forEach(child -> copy.add(freeze(child)));
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("Workflow observations and parameters must be JSON values");
    }
}
