package ai.fabric.execution.manager;

import ai.fabric.execution.specialist.SpecialistId;
import java.time.Instant;
import java.util.Objects;

/**
 * Safe external result. Internal manager directives and provider payloads are
 * intentionally absent.
 */
public record ConversationManagerTurnResult(
    String turnId,
    ConversationManagerId managerId,
    ConversationManagerTurnStatus status,
    String message,
    SpecialistId selectedTarget,
    String managerInvocationId,
    String workerInvocationId,
    String snapshotRevision,
    long snapshotSourceTurnCount,
    ConversationManagerFailure failure,
    boolean replayed,
    Instant startedAt,
    Instant completedAt
) {
    public ConversationManagerTurnResult {
        turnId = requireText(turnId, "turnId");
        Objects.requireNonNull(managerId, "managerId is required");
        Objects.requireNonNull(status, "status is required");
        message = normalizeOptional(message);
        managerInvocationId = normalizeOptional(managerInvocationId);
        workerInvocationId = normalizeOptional(workerInvocationId);
        snapshotRevision = normalizeOptional(snapshotRevision);
        if (snapshotSourceTurnCount < 0) {
            throw new IllegalArgumentException(
                "snapshotSourceTurnCount cannot be negative"
            );
        }
        Objects.requireNonNull(startedAt, "startedAt is required");
        Objects.requireNonNull(completedAt, "completedAt is required");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException(
                "completedAt cannot precede startedAt"
            );
        }
        if (status.succeeded()) {
            validateSuccess(
                status,
                message,
                selectedTarget,
                managerInvocationId,
                workerInvocationId,
                snapshotRevision,
                failure
            );
        } else {
            if (failure == null) {
                throw new IllegalArgumentException(
                    "Failed manager turns require a safe failure"
                );
            }
            if (message != null) {
                throw new IllegalArgumentException(
                    "Failed manager turns cannot contain an external message"
                );
            }
        }
    }

    public boolean succeeded() {
        return status.succeeded();
    }

    public ConversationManagerTurnResult asReplayed() {
        if (replayed) {
            return this;
        }
        return new ConversationManagerTurnResult(
            turnId,
            managerId,
            status,
            message,
            selectedTarget,
            managerInvocationId,
            workerInvocationId,
            snapshotRevision,
            snapshotSourceTurnCount,
            failure,
            true,
            startedAt,
            completedAt
        );
    }

    private static void validateSuccess(
        ConversationManagerTurnStatus status,
        String message,
        SpecialistId selectedTarget,
        String managerInvocationId,
        String workerInvocationId,
        String snapshotRevision,
        ConversationManagerFailure failure
    ) {
        if (message == null
            || managerInvocationId == null
            || snapshotRevision == null
            || failure != null) {
            throw new IllegalArgumentException(
                "Successful manager turns require a validated message, "
                    + "manager invocation, and snapshot"
            );
        }
        if (status == ConversationManagerTurnStatus.SPECIALIST_RESULT) {
            if (selectedTarget == null || workerInvocationId == null) {
                throw new IllegalArgumentException(
                    "Specialist results require target lineage"
                );
            }
        } else if (selectedTarget != null || workerInvocationId != null) {
            throw new IllegalArgumentException(
                "Direct manager responses cannot contain worker lineage"
            );
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
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
