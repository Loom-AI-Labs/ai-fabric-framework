package ai.fabric.execution.delegation;

import java.util.Objects;

public record SpecialistDelegationFailure(
    String reason,
    String publicMessage,
    boolean retryable
) {
    public SpecialistDelegationFailure {
        reason = requireText(reason, "reason");
        publicMessage = requireText(publicMessage, "publicMessage");
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
