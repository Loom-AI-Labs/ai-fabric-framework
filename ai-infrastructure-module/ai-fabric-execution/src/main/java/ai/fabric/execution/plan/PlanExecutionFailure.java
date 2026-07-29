package ai.fabric.execution.plan;

import java.util.Objects;

public record PlanExecutionFailure(
    String reason,
    String publicMessage,
    boolean retryable,
    String stepId
) {
    public PlanExecutionFailure {
        reason = requireText(reason, "reason");
        publicMessage = requireText(publicMessage, "publicMessage");
        stepId = normalizeOptional(stepId);
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

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
