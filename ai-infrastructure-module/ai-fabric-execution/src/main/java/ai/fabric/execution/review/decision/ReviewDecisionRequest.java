package ai.fabric.execution.review.decision;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Safe public decision. Reviewer identity and authority are supplied
 * separately through TrustedReviewerContext.
 */
public record ReviewDecisionRequest(
    String taskId,
    String decisionId,
    ReviewDecisionType decision,
    long expectedVersion,
    JsonNode response
) {

    private static final int MAX_RESPONSE_CHARACTERS = 16_384;

    public ReviewDecisionRequest {
        taskId = requireText(taskId, "taskId", 120);
        decisionId = requireText(decisionId, "decisionId", 160);
        Objects.requireNonNull(decision, "decision is required");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException(
                "expectedVersion must not be negative"
            );
        }
        response = response == null ? null : response.deepCopy();
        if (response != null
            && response.toString().length() > MAX_RESPONSE_CHARACTERS) {
            throw new IllegalArgumentException(
                "response exceeds the maximum size"
            );
        }
    }

    @Override
    public JsonNode response() {
        return response == null ? null : response.deepCopy();
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
