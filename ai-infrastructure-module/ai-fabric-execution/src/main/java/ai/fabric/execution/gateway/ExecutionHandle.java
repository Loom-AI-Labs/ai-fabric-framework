package ai.fabric.execution.gateway;

import java.time.Instant;
import java.util.Objects;

public record ExecutionHandle(
    String invocationId,
    ExecutionDurability durability,
    ExecutionHandleStatus status,
    Instant deadline,
    Instant expiresAt,
    String failureReason
) {
    public ExecutionHandle {
        invocationId = requireText(invocationId, "invocationId");
        Objects.requireNonNull(durability, "durability is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field + " is required").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
