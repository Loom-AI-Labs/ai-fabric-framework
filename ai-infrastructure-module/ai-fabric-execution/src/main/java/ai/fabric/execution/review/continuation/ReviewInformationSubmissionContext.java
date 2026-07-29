package ai.fabric.execution.review.continuation;

import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.review.ReviewTaskView;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

public record ReviewInformationSubmissionContext(
    ReviewTaskView task,
    JsonNode requestedInformation,
    JsonNode suppliedInformation,
    TrustedExecutionContext sourceContext
) {

    public ReviewInformationSubmissionContext {
        Objects.requireNonNull(task, "task is required");
        Objects.requireNonNull(
            requestedInformation,
            "requestedInformation is required"
        );
        Objects.requireNonNull(
            suppliedInformation,
            "suppliedInformation is required"
        );
        requestedInformation = requestedInformation.deepCopy();
        suppliedInformation = suppliedInformation.deepCopy();
        Objects.requireNonNull(
            sourceContext,
            "sourceContext is required"
        );
    }

    @Override
    public JsonNode requestedInformation() {
        return requestedInformation.deepCopy();
    }

    @Override
    public JsonNode suppliedInformation() {
        return suppliedInformation.deepCopy();
    }
}
