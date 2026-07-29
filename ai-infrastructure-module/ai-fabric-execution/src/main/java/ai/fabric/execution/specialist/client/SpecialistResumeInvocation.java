package ai.fabric.execution.specialist.client;

import ai.fabric.execution.context.TrustedExecutionContext;
import java.util.Objects;

/**
 * Host-supplied typed response for a client already bound to one specialist.
 */
public record SpecialistResumeInvocation(
    String invocationId,
    String requestId,
    Object response,
    TrustedExecutionContext trustedExecutionContext,
    String idempotencyKey
) {
    public SpecialistResumeInvocation {
        invocationId = requireText(invocationId, "invocationId");
        requestId = requireText(requestId, "requestId");
        Objects.requireNonNull(response, "response is required");
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
