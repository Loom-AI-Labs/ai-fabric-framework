package ai.fabric.execution.review;

import ai.fabric.execution.review.decision.ReviewDecisionFailure;

public record ReviewTaskCreationResult(
    ReviewTaskView task,
    boolean dispatchAccepted,
    ReviewDecisionFailure failure
) {

    public ReviewTaskCreationResult {
        if (task == null && failure == null) {
            throw new IllegalArgumentException(
                "Unavailable review creation requires a failure"
            );
        }
    }

    public boolean created() {
        return task != null;
    }
}
