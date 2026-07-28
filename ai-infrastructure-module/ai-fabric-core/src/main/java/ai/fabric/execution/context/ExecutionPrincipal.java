package ai.fabric.execution.context;

import java.util.Objects;

/**
 * Verified principal that initiated an orchestration request.
 */
public record ExecutionPrincipal(
    String principalId,
    ExecutionPrincipalType principalType
) {
    public ExecutionPrincipal {
        principalId = requireText(principalId, "principalId");
        Objects.requireNonNull(principalType, "principalType is required");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field + " is required").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
