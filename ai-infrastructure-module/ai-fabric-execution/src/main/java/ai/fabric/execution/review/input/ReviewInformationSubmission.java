package ai.fabric.execution.review.input;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Safe public information response. Source identity is provided separately
 * through the original trusted execution context.
 */
public record ReviewInformationSubmission(
    String taskId,
    String submissionId,
    long expectedVersion,
    JsonNode response
) {

    public ReviewInformationSubmission {
        taskId = requireText(taskId, "taskId", 120);
        submissionId = requireText(
            submissionId,
            "submissionId",
            160
        );
        if (expectedVersion < 0) {
            throw new IllegalArgumentException(
                "expectedVersion must not be negative"
            );
        }
        Objects.requireNonNull(response, "response is required");
        response = response.deepCopy();
        if (response.toString().length() > 16_384) {
            throw new IllegalArgumentException(
                "response exceeds the maximum size"
            );
        }
    }

    @Override
    public JsonNode response() {
        return response.deepCopy();
    }

    private static String requireText(
        String value,
        String field,
        int maxLength
    ) {
        String normalized = Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                field + " must not exceed " + maxLength + " characters"
            );
        }
        return normalized;
    }
}
