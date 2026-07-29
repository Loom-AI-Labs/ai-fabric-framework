package ai.fabric.execution.review;

import ai.fabric.execution.review.decision.ReviewDecisionType;
import ai.fabric.execution.review.policy.ReviewPolicyId;
import ai.fabric.execution.review.policy.ReviewType;
import java.time.Instant;
import java.util.Set;

/**
 * Safe task projection. Source receipts, identities, tenant bindings, and
 * executable action parameters are intentionally absent.
 */
public record ReviewTaskView(
    String taskId,
    ReviewPolicyId policyId,
    ReviewType type,
    String title,
    String summary,
    Set<ReviewDecisionType> allowedDecisions,
    ReviewTaskStatus status,
    Instant createdAt,
    Instant expiresAt,
    long version
) {

    public ReviewTaskView {
        taskId = requireText(taskId, "taskId", 120);
        java.util.Objects.requireNonNull(policyId, "policyId is required");
        java.util.Objects.requireNonNull(type, "type is required");
        title = requireText(title, "title", 160);
        summary = requireText(summary, "summary", 1000);
        allowedDecisions = allowedDecisions == null
            ? Set.of()
            : Set.copyOf(allowedDecisions);
        java.util.Objects.requireNonNull(status, "status is required");
        java.util.Objects.requireNonNull(createdAt, "createdAt is required");
        java.util.Objects.requireNonNull(expiresAt, "expiresAt is required");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
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
