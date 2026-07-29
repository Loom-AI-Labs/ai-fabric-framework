package ai.fabric.execution.plan;

import ai.fabric.execution.context.TrustedExecutionContext;
import java.util.Objects;

/**
 * Host-supplied typed response plus current server-owned context for one
 * waiting plan.
 */
public record PlanExecutionResumeRequest(
    String executionId,
    String requestId,
    Object response,
    TrustedExecutionContext trustedExecutionContext,
    String idempotencyKey
) {
    public PlanExecutionResumeRequest {
        executionId = requireText(executionId, "executionId");
        requestId = requireText(requestId, "requestId");
        Objects.requireNonNull(response, "response is required");
        Objects.requireNonNull(
            trustedExecutionContext,
            "trustedExecutionContext is required"
        );
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.length() > 200) {
            throw new IllegalArgumentException(
                "idempotencyKey must not exceed 200 characters"
            );
        }
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
