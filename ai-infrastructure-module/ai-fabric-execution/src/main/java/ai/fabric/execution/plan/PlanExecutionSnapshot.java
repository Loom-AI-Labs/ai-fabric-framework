package ai.fabric.execution.plan;

import ai.fabric.execution.gateway.ExecutionDurability;
import java.time.Instant;
import java.util.Objects;

public record PlanExecutionSnapshot(
    String executionId,
    ExecutionPlanId planId,
    PlanExecutionStatus status,
    ExecutionDurability durability,
    String activeStepId,
    int completedSteps,
    Instant deadline,
    Instant expiresAt,
    PlanExecutionResult<?> result
) {
    public PlanExecutionSnapshot {
        executionId = requireText(executionId, "executionId");
        Objects.requireNonNull(planId, "planId is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(durability, "durability is required");
        if (completedSteps < 0) {
            throw new IllegalArgumentException(
                "completedSteps must not be negative"
            );
        }
        Objects.requireNonNull(expiresAt, "expiresAt is required");
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
