package ai.fabric.execution.review.auth;

import ai.fabric.execution.review.ReviewTaskView;
import ai.fabric.execution.review.decision.ReviewDecisionType;
import java.util.Objects;

public record ReviewAuthorizationRequest(
    ReviewTaskView task,
    ReviewAuthorizationOperation operation,
    ReviewDecisionType decision
) {

    public ReviewAuthorizationRequest {
        Objects.requireNonNull(task, "task is required");
        Objects.requireNonNull(operation, "operation is required");
        if ((operation == ReviewAuthorizationOperation.DECIDE)
            != (decision != null)) {
            throw new IllegalArgumentException(
                "DECIDE authorization requires an exact decision"
            );
        }
    }
}
