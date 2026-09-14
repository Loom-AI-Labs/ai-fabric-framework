package ai.fabric.execution.chain.state;

import ai.fabric.execution.chain.SpecialistChainExecutionStatus;
import ai.fabric.execution.chain.SpecialistChainId;
import ai.fabric.execution.specialist.SpecialistId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Storage-neutral durable state for one bounded specialist chain. */
public record SpecialistChainExecutionRecord(
    String executionId,
    SpecialistChainId chainId,
    String chainContentHash,
    SpecialistId managerSpecialistId,
    String managerContentHash,
    String accessFingerprint,
    String idempotencyFingerprint,
    String requestFingerprint,
    String protectedRequest,
    String protectedCheckpoint,
    String protectedResult,
    SpecialistChainExecutionStatus status,
    String failureReason,
    int nextDecisionIndex,
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

    public SpecialistChainExecutionRecord {
        executionId = requireText(executionId, "executionId");
        Objects.requireNonNull(chainId, "chainId is required");
        chainContentHash = requireHash(
            chainContentHash,
            "chainContentHash"
        );
        Objects.requireNonNull(
            managerSpecialistId,
            "managerSpecialistId is required"
        );
        managerContentHash = requireHash(
            managerContentHash,
            "managerContentHash"
        );
        accessFingerprint = requireHash(
            accessFingerprint,
            "accessFingerprint"
        );
        idempotencyFingerprint = requireHash(
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
        protectedCheckpoint = requireText(
            protectedCheckpoint,
            "protectedCheckpoint"
        );
        protectedResult = normalizeOptional(protectedResult);
        Objects.requireNonNull(status, "status is required");
        failureReason = normalizeOptional(failureReason);
        if (nextDecisionIndex < 0 || attemptCount < 0 || version < 0) {
            throw new IllegalArgumentException(
                "Record counters must not be negative"
            );
        }
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
        if (status.terminal()) {
            if (completedAt == null || protectedResult == null) {
                throw new IllegalArgumentException(
                    "Terminal chain records require completion and result"
                );
            }
        } else if (completedAt != null || protectedResult != null) {
            throw new IllegalArgumentException(
                "Active chain records cannot contain a terminal result"
            );
        }
    }

    public static SpecialistChainExecutionRecord queued(
        String executionId,
        SpecialistChainId chainId,
        String chainContentHash,
        SpecialistId managerSpecialistId,
        String managerContentHash,
        String accessFingerprint,
        String idempotencyFingerprint,
        String requestFingerprint,
        String protectedRequest,
        String protectedCheckpoint,
        Instant deadline,
        Instant now,
        Duration retention
    ) {
        Objects.requireNonNull(retention, "retention is required");
        return new SpecialistChainExecutionRecord(
            executionId,
            chainId,
            chainContentHash,
            managerSpecialistId,
            managerContentHash,
            accessFingerprint,
            idempotencyFingerprint,
            requestFingerprint,
            protectedRequest,
            protectedCheckpoint,
            null,
            SpecialistChainExecutionStatus.QUEUED,
            null,
            0,
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

    public boolean claimable(Instant now, int maximumAttempts) {
        Objects.requireNonNull(now, "now is required");
        if (status.terminal()
            || !now.isBefore(deadline)
            || attemptCount >= maximumAttempts) {
            return false;
        }
        return status == SpecialistChainExecutionStatus.QUEUED
            || (status == SpecialistChainExecutionStatus.RUNNING
                && leaseUntil != null
                && !leaseUntil.isAfter(now));
    }

    public SpecialistChainExecutionRecord claimed(
        String workerId,
        Instant now,
        Instant newLeaseUntil
    ) {
        workerId = requireText(workerId, "workerId");
        Objects.requireNonNull(now, "now is required");
        Objects.requireNonNull(newLeaseUntil, "newLeaseUntil is required");
        if (!newLeaseUntil.isAfter(now)) {
            throw new IllegalArgumentException(
                "newLeaseUntil must be after now"
            );
        }
        return copy(
            protectedCheckpoint,
            null,
            SpecialistChainExecutionStatus.RUNNING,
            null,
            nextDecisionIndex,
            now,
            null,
            expiresAt,
            workerId,
            newLeaseUntil,
            attemptCount + 1
        );
    }

    public SpecialistChainExecutionRecord checkpointed(
        String checkpoint,
        int decisionIndex,
        Instant now,
        Instant newLeaseUntil
    ) {
        if (status != SpecialistChainExecutionStatus.RUNNING) {
            throw new IllegalStateException(
                "Only running chains may checkpoint"
            );
        }
        checkpoint = requireText(checkpoint, "checkpoint");
        if (decisionIndex < nextDecisionIndex) {
            throw new IllegalArgumentException(
                "decisionIndex cannot move backwards"
            );
        }
        return copy(
            checkpoint,
            null,
            SpecialistChainExecutionStatus.RUNNING,
            null,
            decisionIndex,
            now,
            null,
            expiresAt,
            leaseOwner,
            newLeaseUntil,
            attemptCount
        );
    }

    public SpecialistChainExecutionRecord completed(
        SpecialistChainExecutionStatus terminalStatus,
        String result,
        String reason,
        Instant now,
        Duration retention
    ) {
        if (!terminalStatus.terminal()) {
            throw new IllegalArgumentException(
                "completed status must be terminal"
            );
        }
        Objects.requireNonNull(now, "now is required");
        Objects.requireNonNull(retention, "retention is required");
        return copy(
            protectedCheckpoint,
            requireText(result, "result"),
            terminalStatus,
            reason,
            nextDecisionIndex,
            now,
            now,
            now.plus(retention),
            null,
            null,
            attemptCount
        );
    }

    private SpecialistChainExecutionRecord copy(
        String checkpoint,
        String result,
        SpecialistChainExecutionStatus newStatus,
        String reason,
        int decisionIndex,
        Instant newUpdatedAt,
        Instant newCompletedAt,
        Instant newExpiresAt,
        String newLeaseOwner,
        Instant newLeaseUntil,
        int newAttemptCount
    ) {
        return new SpecialistChainExecutionRecord(
            executionId,
            chainId,
            chainContentHash,
            managerSpecialistId,
            managerContentHash,
            accessFingerprint,
            idempotencyFingerprint,
            requestFingerprint,
            protectedRequest,
            checkpoint,
            result,
            newStatus,
            reason,
            decisionIndex,
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

    private static Instant later(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
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
