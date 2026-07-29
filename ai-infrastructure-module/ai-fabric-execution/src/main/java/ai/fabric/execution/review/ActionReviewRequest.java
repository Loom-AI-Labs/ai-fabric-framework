package ai.fabric.execution.review;

import ai.fabric.execution.review.policy.ReviewPolicyId;

/**
 * Application-selected request to place an existing action proposal into a
 * durable review lifecycle.
 */
public record ActionReviewRequest(
    String receiptId,
    ReviewPolicyId policyId,
    String title,
    String summary,
    String idempotencyKey
) {

    public ActionReviewRequest {
        receiptId = requireText(receiptId, "receiptId", 120);
        java.util.Objects.requireNonNull(policyId, "policyId is required");
        title = requireText(title, "title", 160);
        summary = requireText(summary, "summary", 1000);
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
        String normalized = java.util.Objects.requireNonNull(
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
