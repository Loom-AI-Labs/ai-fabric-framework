package ai.fabric.execution.review.input;

import ai.fabric.execution.review.ReviewTaskView;
import ai.fabric.execution.review.decision.ReviewDecisionFailure;

public record ReviewInformationResult(
    ReviewTaskView task,
    String message,
    ReviewDecisionFailure failure
) {

    public ReviewInformationResult {
        message = normalize(message);
        if (task == null && failure == null) {
            throw new IllegalArgumentException(
                "Unavailable information result requires a failure"
            );
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
