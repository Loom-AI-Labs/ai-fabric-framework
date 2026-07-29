package ai.fabric.execution.delegation;

import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.specialist.SpecialistId;
import java.time.Instant;
import java.util.Objects;

/**
 * Backend-owned request to invoke one declared child from a validated parent.
 */
public record SpecialistDelegationRequest<P, I>(
    AIExecutionResult<P> sourceExecution,
    SpecialistId targetSpecialistId,
    I targetInput,
    TrustedExecutionContext trustedExecutionContext,
    Instant deadline,
    String idempotencyKey
) {
    public SpecialistDelegationRequest {
        Objects.requireNonNull(sourceExecution, "sourceExecution is required");
        Objects.requireNonNull(
            targetSpecialistId,
            "targetSpecialistId is required"
        );
        Objects.requireNonNull(targetInput, "targetInput is required");
        Objects.requireNonNull(
            trustedExecutionContext,
            "trustedExecutionContext is required"
        );
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
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
