package ai.fabric.execution.review.dispatch;

import ai.fabric.execution.review.ReviewTaskView;
import java.util.Objects;

/**
 * Safe delivery envelope. It contains no executable source, identity, or
 * reviewer authority.
 */
public record ReviewDispatchRequest(
    String dispatchId,
    ReviewTaskView task,
    String idempotencyKey
) {

    public ReviewDispatchRequest {
        dispatchId = requireText(dispatchId, "dispatchId", 120);
        Objects.requireNonNull(task, "task is required");
        idempotencyKey = requireText(
            idempotencyKey,
            "idempotencyKey",
            200
        );
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
