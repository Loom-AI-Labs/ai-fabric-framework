package ai.fabric.execution.gateway;

import java.util.Objects;

/** Trusted host request referencing durable work authority, never a public principal/assertion. */
public record GoalExecutionRequest(AIExecutionRequest<?> request, String authorityReference) {
    public GoalExecutionRequest {
        Objects.requireNonNull(request);
        if (authorityReference == null || authorityReference.isBlank() || authorityReference.length() > 512)
            throw new IllegalArgumentException("A durable goal authority reference is required");
    }
}
