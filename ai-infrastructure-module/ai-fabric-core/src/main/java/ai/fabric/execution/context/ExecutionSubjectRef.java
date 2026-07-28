package ai.fabric.execution.context;

import java.util.Objects;

/**
 * Application-owned subject whose data or state is being processed.
 */
public record ExecutionSubjectRef(
    String subjectType,
    String subjectId
) {
    public ExecutionSubjectRef {
        subjectType = requireText(subjectType, "subjectType");
        subjectId = requireText(subjectId, "subjectId");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field + " is required").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
