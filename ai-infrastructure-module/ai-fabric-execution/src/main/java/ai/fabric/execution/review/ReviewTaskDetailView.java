package ai.fabric.execution.review;

import ai.fabric.execution.action.ActionOutcomeView;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Authorized review detail. Information payloads remain encrypted in the
 * repository and are projected only after reviewer authorization.
 */
public record ReviewTaskDetailView(
    ReviewTaskView task,
    JsonNode requestedInformation,
    JsonNode suppliedInformation,
    String message,
    ActionOutcomeView outcome,
    String successorTaskId,
    String failureReason
) {

    public ReviewTaskDetailView {
        Objects.requireNonNull(task, "task is required");
        requestedInformation = copy(requestedInformation);
        suppliedInformation = copy(suppliedInformation);
        message = normalize(message);
        successorTaskId = normalize(successorTaskId);
        failureReason = normalize(failureReason);
    }

    @Override
    public JsonNode requestedInformation() {
        return copy(requestedInformation);
    }

    @Override
    public JsonNode suppliedInformation() {
        return copy(suppliedInformation);
    }

    private static JsonNode copy(JsonNode value) {
        return value == null ? null : value.deepCopy();
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
