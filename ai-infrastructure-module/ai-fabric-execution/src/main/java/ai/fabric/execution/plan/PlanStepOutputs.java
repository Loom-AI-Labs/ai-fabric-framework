package ai.fabric.execution.plan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable, allowlisted view of completed step outputs.
 */
public final class PlanStepOutputs {

    private final Map<String, Object> outputs;

    public PlanStepOutputs(Map<String, ?> outputs) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (outputs != null) {
            outputs.forEach((stepId, output) -> {
                String normalized = requireText(stepId, "stepId");
                copy.put(
                    normalized,
                    Objects.requireNonNull(
                        output,
                        "output is required for " + normalized
                    )
                );
            });
        }
        this.outputs = Collections.unmodifiableMap(copy);
    }

    public Set<String> stepIds() {
        return outputs.keySet();
    }

    public <T> T require(String stepId, Class<T> type) {
        return find(stepId, type).orElseThrow(() ->
            new IllegalArgumentException(
                "No approved " + type.getName() + " output exists for " + stepId
            )
        );
    }

    public <T> Optional<T> find(String stepId, Class<T> type) {
        Objects.requireNonNull(type, "type is required");
        Object output = outputs.get(requireText(stepId, "stepId"));
        if (output == null) {
            return Optional.empty();
        }
        if (!type.isInstance(output)) {
            throw new IllegalArgumentException(
                "Approved output for " + stepId + " is not " + type.getName()
            );
        }
        return Optional.of(type.cast(output));
    }

    public int size() {
        return outputs.size();
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
