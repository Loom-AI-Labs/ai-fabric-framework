package ai.fabric.execution.plan;

import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.TrustedExecutionContext;
import java.time.Instant;
import java.util.Objects;

/**
 * Typed request to an application-selected fixed plan.
 */
public record PlanExecutionRequest<I>(
    ExecutionPlanId planId,
    I input,
    TrustedExecutionContext trustedExecutionContext,
    Instant deadline,
    String idempotencyKey
) {
    public PlanExecutionRequest {
        Objects.requireNonNull(planId, "planId is required");
        Objects.requireNonNull(input, "input is required");
        Objects.requireNonNull(
            trustedExecutionContext,
            "trustedExecutionContext is required"
        );
        if (trustedExecutionContext.source() == ExecutionSource.INTERACTIVE) {
            throw new IllegalArgumentException(
                "Interactive execution plans require dialogue ownership, which is not available in this release"
            );
        }
        idempotencyKey = normalizeOptional(idempotencyKey);
        if (idempotencyKey != null && idempotencyKey.length() > 200) {
            throw new IllegalArgumentException(
                "idempotencyKey must not exceed 200 characters"
            );
        }
    }

    public static <I> PlanExecutionRequest<I> synchronous(
        ExecutionPlanId planId,
        I input,
        TrustedExecutionContext trustedExecutionContext
    ) {
        return new PlanExecutionRequest<>(
            planId,
            input,
            trustedExecutionContext,
            null,
            null
        );
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
