package ai.fabric.execution.review.decision;

import ai.fabric.execution.action.ActionOutcomeView;
import ai.fabric.execution.review.ReviewTaskView;

public record ReviewDecisionResult(
    ReviewTaskView task,
    ActionOutcomeView outcome,
    String successorTaskId,
    ReviewDecisionFailure failure
) {

    public ReviewDecisionResult {
        if (task == null && failure == null) {
            throw new IllegalArgumentException(
                "Unavailable review result requires a failure"
            );
        }
        successorTaskId = normalizeOptional(successorTaskId);
    }

    public boolean succeeded() {
        return failure == null && task != null && task.status().terminal();
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
