package ai.fabric.execution.handoff;

import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.specialist.SpecialistId;
import java.time.Instant;
import java.util.Objects;

/**
 * Backend-owned request to transfer responsibility to one declared successor.
 */
public record SpecialistHandoffRequest<P, I>(
    AIExecutionResult<P> predecessorExecution,
    SpecialistId successorSpecialistId,
    I successorInput,
    TrustedExecutionContext trustedExecutionContext,
    Instant deadline,
    String idempotencyKey
) {
    public SpecialistHandoffRequest {
        Objects.requireNonNull(
            predecessorExecution,
            "predecessorExecution is required"
        );
        Objects.requireNonNull(
            successorSpecialistId,
            "successorSpecialistId is required"
        );
        Objects.requireNonNull(successorInput, "successorInput is required");
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
