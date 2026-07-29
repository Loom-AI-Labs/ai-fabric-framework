package ai.fabric.execution.review.persistence;

import ai.fabric.execution.review.dispatch.ReviewDispatchStatus;
import java.time.Instant;
import java.util.Objects;

public record ReviewDispatchRecord(
    String dispatchId,
    String taskId,
    String dispatcherId,
    int attemptNumber,
    String idempotencyKey,
    ReviewDispatchStatus status,
    String externalReference,
    String failureReason,
    Instant createdAt,
    Instant completedAt,
    long version
) {

    public ReviewDispatchRecord {
        dispatchId = requireText(dispatchId, "dispatchId", 120);
        taskId = requireText(taskId, "taskId", 120);
        dispatcherId = requireText(dispatcherId, "dispatcherId", 160);
        if (attemptNumber < 1) {
            throw new IllegalArgumentException(
                "attemptNumber must be positive"
            );
        }
        idempotencyKey = requireText(
            idempotencyKey,
            "idempotencyKey",
            200
        );
        Objects.requireNonNull(status, "status is required");
        externalReference = optional(
            externalReference,
            "externalReference",
            240
        );
        failureReason = optional(failureReason, "failureReason", 160);
        Objects.requireNonNull(createdAt, "createdAt is required");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    public ReviewDispatchRecord completed(
        ReviewDispatchStatus nextStatus,
        String reference,
        String reason,
        Instant now
    ) {
        if (status != ReviewDispatchStatus.PENDING
            || nextStatus == ReviewDispatchStatus.PENDING) {
            throw new IllegalStateException(
                "Only a pending dispatch can complete"
            );
        }
        return new ReviewDispatchRecord(
            dispatchId,
            taskId,
            dispatcherId,
            attemptNumber,
            idempotencyKey,
            nextStatus,
            reference,
            reason,
            createdAt,
            Objects.requireNonNull(now, "now is required"),
            version + 1
        );
    }

    private static String optional(
        String value,
        String field,
        int maxLength
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireText(value, field, maxLength);
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
