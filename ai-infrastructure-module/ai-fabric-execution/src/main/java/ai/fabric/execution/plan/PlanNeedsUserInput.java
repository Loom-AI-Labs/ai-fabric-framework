package ai.fabric.execution.plan;

import ai.fabric.execution.input.NeedsUserInput;
import java.util.Objects;

/**
 * One typed child input wait, correlated to its enclosing plan and step.
 */
public record PlanNeedsUserInput(
    String executionId,
    ExecutionPlanId planId,
    String stepId,
    NeedsUserInput request
) {
    public PlanNeedsUserInput {
        executionId = requireText(executionId, "executionId");
        Objects.requireNonNull(planId, "planId is required");
        stepId = requireText(stepId, "stepId");
        Objects.requireNonNull(request, "request is required");
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
