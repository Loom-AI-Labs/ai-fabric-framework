package ai.fabric.execution.review.continuation;

import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.review.ReviewTaskView;
import ai.fabric.execution.review.TrustedReviewerContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

public record ReviewInformationRequestContext(
    ReviewTaskView task,
    JsonNode request,
    TrustedExecutionContext sourceContext,
    TrustedReviewerContext reviewer
) {

    public ReviewInformationRequestContext {
        Objects.requireNonNull(task, "task is required");
        Objects.requireNonNull(request, "request is required");
        request = request.deepCopy();
        Objects.requireNonNull(
            sourceContext,
            "sourceContext is required"
        );
        Objects.requireNonNull(reviewer, "reviewer is required");
    }

    @Override
    public JsonNode request() {
        return request.deepCopy();
    }
}
