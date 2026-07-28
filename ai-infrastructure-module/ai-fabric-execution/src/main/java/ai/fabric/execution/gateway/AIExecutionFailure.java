package ai.fabric.execution.gateway;

import java.util.Objects;

public record AIExecutionFailure(
    String reason,
    String publicMessage,
    boolean retryable
) {
    public AIExecutionFailure {
        reason = requireText(reason, "reason");
        publicMessage = requireText(publicMessage, "publicMessage");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field + " is required").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
