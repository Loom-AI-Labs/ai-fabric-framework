package ai.fabric.execution.review.persistence;

import ai.fabric.execution.review.ReviewSourceType;
import ai.fabric.execution.review.ReviewTaskStatus;
import ai.fabric.execution.review.decision.ReviewDecisionType;
import ai.fabric.execution.review.policy.ReviewPolicyId;
import ai.fabric.execution.review.policy.ReviewType;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Protected durable state for one review task.
 */
public record ReviewTaskRecord(
    String taskId,
    ReviewPolicyId policyId,
    String policyContentHash,
    ReviewType reviewType,
    ReviewSourceType sourceType,
    String sourceFingerprint,
    String initiatorFingerprint,
    String subjectFingerprint,
    String tenantFingerprint,
    String deploymentFingerprint,
    String idempotencyFingerprint,
    String requestFingerprint,
    String protectedSource,
    String protectedPresentation,
    Set<ReviewDecisionType> allowedDecisions,
    ReviewTaskStatus status,
    ReviewDecisionType decisionType,
    String decisionFingerprint,
    String reviewerFingerprint,
    String protectedDecision,
    String protectedResult,
    String failureReason,
    String successorTaskId,
    Instant createdAt,
    Instant expiresAt,
    Instant updatedAt,
    Instant terminalAt,
    String leaseOwner,
    Instant leaseUntil,
    int attemptCount,
    long version
) {

    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");

    public ReviewTaskRecord {
        taskId = requireText(taskId, "taskId", 120);
        Objects.requireNonNull(policyId, "policyId is required");
        policyContentHash = requireHash(
            policyContentHash,
            "policyContentHash"
        );
        Objects.requireNonNull(reviewType, "reviewType is required");
        Objects.requireNonNull(sourceType, "sourceType is required");
        sourceFingerprint = requireHash(
            sourceFingerprint,
            "sourceFingerprint"
        );
        initiatorFingerprint = requireHash(
            initiatorFingerprint,
            "initiatorFingerprint"
        );
        subjectFingerprint = requireHash(
            subjectFingerprint,
            "subjectFingerprint"
        );
        tenantFingerprint = requireHash(
            tenantFingerprint,
            "tenantFingerprint"
        );
        deploymentFingerprint = requireHash(
            deploymentFingerprint,
            "deploymentFingerprint"
        );
        idempotencyFingerprint = requireHash(
            idempotencyFingerprint,
            "idempotencyFingerprint"
        );
        requestFingerprint = requireHash(
            requestFingerprint,
            "requestFingerprint"
        );
        protectedSource = requireText(
            protectedSource,
            "protectedSource",
            Integer.MAX_VALUE
        );
        protectedPresentation = requireText(
            protectedPresentation,
            "protectedPresentation",
            Integer.MAX_VALUE
        );
        allowedDecisions = allowedDecisions == null
            ? Set.of()
            : Set.copyOf(allowedDecisions);
        if (allowedDecisions.isEmpty()) {
            throw new IllegalArgumentException(
                "allowedDecisions must not be empty"
            );
        }
        Objects.requireNonNull(status, "status is required");
        decisionFingerprint = optionalHash(
            decisionFingerprint,
            "decisionFingerprint"
        );
        reviewerFingerprint = optionalHash(
            reviewerFingerprint,
            "reviewerFingerprint"
        );
        protectedDecision = normalizeOptional(protectedDecision);
        protectedResult = normalizeOptional(protectedResult);
        failureReason = optionalText(failureReason, "failureReason", 160);
        successorTaskId = optionalText(
            successorTaskId,
            "successorTaskId",
            120
        );
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException(
                "expiresAt must be after createdAt"
            );
        }
        leaseOwner = optionalText(leaseOwner, "leaseOwner", 160);
        if ((leaseOwner == null) != (leaseUntil == null)) {
            throw new IllegalArgumentException(
                "leaseOwner and leaseUntil must be set together"
            );
        }
        if (attemptCount < 0 || version < 0) {
            throw new IllegalArgumentException(
                "attemptCount and version must not be negative"
            );
        }
    }

    public ReviewTaskRecord claim(
        ReviewDecisionType decision,
        String decisionHash,
        String reviewerHash,
        String protectedPayload,
        String workerId,
        Instant now,
        Instant newLeaseUntil
    ) {
        if (status != ReviewTaskStatus.WAITING_FOR_REVIEW) {
            throw new IllegalStateException(
                "Review task is not waiting for a decision"
            );
        }
        if (!allowedDecisions.contains(decision)) {
            throw new IllegalArgumentException(
                "Decision is not allowed by the pinned review policy"
            );
        }
        Objects.requireNonNull(now, "now is required");
        if (!newLeaseUntil.isAfter(now)) {
            throw new IllegalArgumentException(
                "leaseUntil must be after now"
            );
        }
        return copy(
            ReviewTaskStatus.DECIDING,
            decision,
            requireHash(decisionHash, "decisionFingerprint"),
            requireHash(reviewerHash, "reviewerFingerprint"),
            requireText(
                protectedPayload,
                "protectedDecision",
                Integer.MAX_VALUE
            ),
            null,
            null,
            null,
            now,
            null,
            requireText(workerId, "workerId", 160),
            newLeaseUntil,
            attemptCount + 1
        );
    }

    public ReviewTaskRecord reclaim(
        String workerId,
        Instant now,
        Instant newLeaseUntil
    ) {
        if (status != ReviewTaskStatus.DECIDING
            || leaseUntil == null
            || leaseUntil.isAfter(now)) {
            throw new IllegalStateException(
                "Review task does not have an expired decision lease"
            );
        }
        if (!newLeaseUntil.isAfter(now)) {
            throw new IllegalArgumentException(
                "leaseUntil must be after now"
            );
        }
        return copy(
            status,
            decisionType,
            decisionFingerprint,
            reviewerFingerprint,
            protectedDecision,
            protectedResult,
            failureReason,
            successorTaskId,
            now,
            terminalAt,
            requireText(workerId, "workerId", 160),
            newLeaseUntil,
            attemptCount + 1
        );
    }

    public ReviewTaskRecord waitingForInformation(
        String result,
        Instant now
    ) {
        requireDeciding();
        return copy(
            ReviewTaskStatus.WAITING_FOR_INFORMATION,
            decisionType,
            decisionFingerprint,
            reviewerFingerprint,
            protectedDecision,
            requireText(result, "protectedResult", Integer.MAX_VALUE),
            null,
            null,
            now,
            null,
            null,
            null,
            attemptCount
        );
    }

    public ReviewTaskRecord informationProvided(
        String result,
        Instant now
    ) {
        if (status != ReviewTaskStatus.WAITING_FOR_INFORMATION) {
            throw new IllegalStateException(
                "Review task is not waiting for information"
            );
        }
        return copy(
            ReviewTaskStatus.WAITING_FOR_REVIEW,
            null,
            null,
            null,
            null,
            requireText(result, "protectedResult", Integer.MAX_VALUE),
            null,
            null,
            now,
            null,
            null,
            null,
            attemptCount
        );
    }

    public ReviewTaskRecord completed(
        ReviewTaskStatus terminalStatus,
        String result,
        String successor,
        Instant now
    ) {
        requireDeciding();
        if (!terminalStatus.terminal()
            || terminalStatus == ReviewTaskStatus.EXPIRED
            || terminalStatus == ReviewTaskStatus.FAILED) {
            throw new IllegalArgumentException(
                "Decision completion requires a decision terminal status"
            );
        }
        return copy(
            terminalStatus,
            decisionType,
            decisionFingerprint,
            reviewerFingerprint,
            protectedDecision,
            normalizeOptional(result),
            null,
            normalizeOptional(successor),
            now,
            now,
            null,
            null,
            attemptCount
        );
    }

    public ReviewTaskRecord failed(String reason, Instant now) {
        return failed(reason, protectedResult, now);
    }

    public ReviewTaskRecord failed(
        String reason,
        String result,
        Instant now
    ) {
        if (status.terminal()) {
            throw new IllegalStateException(
                "Terminal review task cannot fail again"
            );
        }
        return copy(
            ReviewTaskStatus.FAILED,
            decisionType,
            decisionFingerprint,
            reviewerFingerprint,
            protectedDecision,
            normalizeOptional(result),
            requireText(reason, "reason", 160),
            successorTaskId,
            now,
            now,
            null,
            null,
            attemptCount
        );
    }

    public ReviewTaskRecord expired(Instant now) {
        if (status != ReviewTaskStatus.WAITING_FOR_REVIEW
            && status != ReviewTaskStatus.WAITING_FOR_INFORMATION) {
            throw new IllegalStateException(
                "Only a waiting review task can expire"
            );
        }
        return copy(
            ReviewTaskStatus.EXPIRED,
            decisionType,
            decisionFingerprint,
            reviewerFingerprint,
            protectedDecision,
            protectedResult,
            "REVIEW_TASK_EXPIRED",
            successorTaskId,
            now,
            now,
            null,
            null,
            attemptCount
        );
    }

    private ReviewTaskRecord copy(
        ReviewTaskStatus nextStatus,
        ReviewDecisionType nextDecision,
        String nextDecisionFingerprint,
        String nextReviewerFingerprint,
        String nextProtectedDecision,
        String nextProtectedResult,
        String nextFailureReason,
        String nextSuccessorTaskId,
        Instant nextUpdatedAt,
        Instant nextTerminalAt,
        String nextLeaseOwner,
        Instant nextLeaseUntil,
        int nextAttemptCount
    ) {
        return new ReviewTaskRecord(
            taskId,
            policyId,
            policyContentHash,
            reviewType,
            sourceType,
            sourceFingerprint,
            initiatorFingerprint,
            subjectFingerprint,
            tenantFingerprint,
            deploymentFingerprint,
            idempotencyFingerprint,
            requestFingerprint,
            protectedSource,
            protectedPresentation,
            allowedDecisions,
            nextStatus,
            nextDecision,
            nextDecisionFingerprint,
            nextReviewerFingerprint,
            nextProtectedDecision,
            nextProtectedResult,
            nextFailureReason,
            nextSuccessorTaskId,
            createdAt,
            expiresAt,
            nextUpdatedAt,
            nextTerminalAt,
            nextLeaseOwner,
            nextLeaseUntil,
            nextAttemptCount,
            version + 1
        );
    }

    private void requireDeciding() {
        if (status != ReviewTaskStatus.DECIDING) {
            throw new IllegalStateException(
                "Review task is not being decided"
            );
        }
    }

    private static String optionalHash(String value, String field) {
        String normalized = normalizeOptional(value);
        return normalized == null ? null : requireHash(normalized, field);
    }

    private static String requireHash(String value, String field) {
        String normalized = requireText(value, field, 64);
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                field + " must be a lowercase SHA-256 value"
            );
        }
        return normalized;
    }

    private static String optionalText(
        String value,
        String field,
        int maxLength
    ) {
        String normalized = normalizeOptional(value);
        return normalized == null
            ? null
            : requireText(normalized, field, maxLength);
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

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
