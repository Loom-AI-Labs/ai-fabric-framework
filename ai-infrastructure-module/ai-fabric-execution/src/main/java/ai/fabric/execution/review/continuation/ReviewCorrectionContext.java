package ai.fabric.execution.review.continuation;

import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.review.ReviewTaskView;
import ai.fabric.execution.review.TrustedReviewerContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

public record ReviewCorrectionContext(
    ReviewTaskView task,
    JsonNode correction,
    TrustedExecutionContext sourceContext,
    TrustedReviewerContext reviewer
) {

    public ReviewCorrectionContext {
        Objects.requireNonNull(task, "task is required");
        Objects.requireNonNull(correction, "correction is required");
        correction = correction.deepCopy();
        Objects.requireNonNull(
            sourceContext,
            "sourceContext is required"
        );
        Objects.requireNonNull(reviewer, "reviewer is required");
    }

    @Override
    public JsonNode correction() {
        return correction.deepCopy();
    }
}
