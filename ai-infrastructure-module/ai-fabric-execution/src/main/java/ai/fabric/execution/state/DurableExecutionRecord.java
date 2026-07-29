package ai.fabric.execution.state;

import ai.fabric.execution.gateway.ExecutionHandleStatus;
import ai.fabric.execution.specialist.SpecialistId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Storage-neutral durable state for one bounded specialist job.
 */
public record DurableExecutionRecord(
    String invocationId,
    SpecialistId specialistId,
    String specialistContentHash,
    String accessFingerprint,
    String idempotencyFingerprint,
    String requestFingerprint,
    String protectedRequest,
    String protectedResult,
    ExecutionHandleStatus status,
    String failureReason,
    Instant deadline,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt,
    Instant expiresAt,
    String leaseOwner,
    Instant leaseUntil,
    int attemptCount,
    long version
) {
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");

    public DurableExecutionRecord {
        invocationId = requireText(invocationId, "invocationId");
        Objects.requireNonNull(specialistId, "specialistId is required");
        specialistContentHash = requireHash(
            specialistContentHash,
            "specialistContentHash"
        );
        accessFingerprint = requireHash(
            accessFingerprint,
            "accessFingerprint"
        );
        idempotencyFingerprint = optionalHash(
            idempotencyFingerprint,
            "idempotencyFingerprint"
        );
        requestFingerprint = requireHash(
            requestFingerprint,
            "requestFingerprint"
        );
        protectedRequest = requireText(
            protectedRequest,
            "protectedRequest"
        );
        Objects.requireNonNull(status, "status is required");
        failureReason = normalizeOptional(failureReason);
        Objects.requireNonNull(deadline, "deadline is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
        leaseOwner = normalizeOptional(leaseOwner);
        if ((leaseOwner == null) != (leaseUntil == null)) {
            throw new IllegalArgumentException(
                "leaseOwner and leaseUntil must be set together"
            );
        }
        if (attemptCount < 0) {
            throw new IllegalArgumentException(
                "attemptCount must not be negative"
            );
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        if (isTerminal(status) && completedAt == null) {
            throw new IllegalArgumentException(
                "terminal execution requires completedAt"
            );
        }
    }

    public static DurableExecutionRecord queued(
        String invocationId,
        SpecialistId specialistId,
        String specialistContentHash,
        String accessFingerprint,
        String idempotencyFingerprint,
        String requestFingerprint,
        String protectedRequest,
        Instant deadline,
        Instant now,
        Duration retention
    ) {
        Objects.requireNonNull(retention, "retention is required");
        return new DurableExecutionRecord(
            invocationId,
            specialistId,
            specialistContentHash,
            accessFingerprint,
            idempotencyFingerprint,
            requestFingerprint,
            protectedRequest,
            null,
            ExecutionHandleStatus.QUEUED,
            null,
            deadline,
            now,
            now,
            null,
            later(deadline, now).plus(retention),
            null,
            null,
            0,
            0
        );
    }

    public boolean terminal() {
        return isTerminal(status);
    }

    public boolean claimable(Instant now, int maxAttempts) {
        Objects.requireNonNull(now, "now is required");
        if (terminal() || !now.isBefore(deadline)
            || attemptCount >= maxAttempts) {
            return false;
        }
        return status == ExecutionHandleStatus.QUEUED
            || (
                status == ExecutionHandleStatus.RUNNING
                    && leaseUntil != null
                    && !leaseUntil.isAfter(now)
            );
    }

    public DurableExecutionRecord claimed(
        String workerId,
        Instant now,
        Instant newLeaseUntil
    ) {
        workerId = requireText(workerId, "workerId");
        Objects.requireNonNull(now, "now is required");
        Objects.requireNonNull(
            newLeaseUntil,
            "newLeaseUntil is required"
        );
        if (!newLeaseUntil.isAfter(now)) {
            throw new IllegalArgumentException(
                "newLeaseUntil must be after now"
            );
        }
        return copy(
            protectedResult,
            ExecutionHandleStatus.RUNNING,
            null,
            now,
            completedAt,
            expiresAt,
            workerId,
            newLeaseUntil,
            attemptCount + 1
        );
    }

    public DurableExecutionRecord completed(
        ExecutionHandleStatus terminalStatus,
        String result,
        String reason,
        Instant now,
        Duration retention
    ) {
        if (!isTerminal(terminalStatus)) {
            throw new IllegalArgumentException(
                "completed status must be terminal"
            );
        }
        Objects.requireNonNull(now, "now is required");
        Objects.requireNonNull(retention, "retention is required");
        return copy(
            result,
            terminalStatus,
            reason,
            now,
            now,
            now.plus(retention),
            null,
            null,
            attemptCount
        );
    }

    private DurableExecutionRecord copy(
        String result,
        ExecutionHandleStatus newStatus,
        String reason,
        Instant newUpdatedAt,
        Instant newCompletedAt,
        Instant newExpiresAt,
        String newLeaseOwner,
        Instant newLeaseUntil,
        int newAttemptCount
    ) {
        return new DurableExecutionRecord(
            invocationId,
            specialistId,
            specialistContentHash,
            accessFingerprint,
            idempotencyFingerprint,
            requestFingerprint,
            protectedRequest,
            result,
            newStatus,
            reason,
            deadline,
            createdAt,
            newUpdatedAt,
            newCompletedAt,
            newExpiresAt,
            newLeaseOwner,
            newLeaseUntil,
            newAttemptCount,
            version + 1
        );
    }

    private static boolean isTerminal(ExecutionHandleStatus status) {
        return status == ExecutionHandleStatus.SUCCEEDED
            || status == ExecutionHandleStatus.FAILED
            || status == ExecutionHandleStatus.CANCELLED
            || status == ExecutionHandleStatus.REJECTED
            || status == ExecutionHandleStatus.EXPIRED;
    }

    private static Instant later(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private static String optionalHash(String value, String field) {
        String normalized = normalizeOptional(value);
        return normalized == null ? null : requireHash(normalized, field);
    }

    private static String requireHash(String value, String field) {
        String normalized = requireText(value, field);
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                field + " must be a lowercase SHA-256 value"
            );
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
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
