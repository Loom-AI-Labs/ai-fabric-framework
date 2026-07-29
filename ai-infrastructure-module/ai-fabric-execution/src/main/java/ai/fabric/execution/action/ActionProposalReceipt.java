package ai.fabric.execution.action;

import ai.fabric.execution.specialist.SpecialistId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Durable, identity-bound receipt for one validated action proposal.
 */
public record ActionProposalReceipt(
    String receiptId,
    String invocationId,
    SpecialistId specialistId,
    String specialistContentHash,
    String effectiveProfileHash,
    String principalFingerprint,
    String subjectType,
    String subjectFingerprint,
    String tenantFingerprint,
    String deploymentFingerprint,
    String actionName,
    String protectedParameters,
    String parameterHash,
    String parameterSchemaHash,
    String confirmationMessage,
    String idempotencyKey,
    List<String> evidenceHashes,
    ActionProposalReceiptStatus status,
    Instant createdAt,
    Instant expiresAt,
    Instant confirmedAt,
    Instant executionStartedAt,
    Instant executedAt,
    Instant terminalAt,
    String protectedOutcome,
    String failureReason,
    Instant updatedAt,
    long version
) {
    public ActionProposalReceipt {
        receiptId = requireText(receiptId, "receiptId", 120);
        invocationId = requireText(invocationId, "invocationId", 120);
        Objects.requireNonNull(specialistId, "specialistId is required");
        requireText(specialistId.name(), "specialistId.name", 120);
        requireText(specialistId.version(), "specialistId.version", 80);
        specialistContentHash = requireText(
            specialistContentHash,
            "specialistContentHash",
            128
        );
        effectiveProfileHash = requireText(
            effectiveProfileHash,
            "effectiveProfileHash",
            128
        );
        principalFingerprint = requireText(
            principalFingerprint,
            "principalFingerprint",
            128
        );
        subjectType = requireText(subjectType, "subjectType", 80);
        subjectFingerprint = requireText(
            subjectFingerprint,
            "subjectFingerprint",
            128
        );
        tenantFingerprint = requireText(
            tenantFingerprint,
            "tenantFingerprint",
            128
        );
        deploymentFingerprint = requireText(
            deploymentFingerprint,
            "deploymentFingerprint",
            128
        );
        actionName = requireText(actionName, "actionName", 160);
        protectedParameters = requireText(
            protectedParameters,
            "protectedParameters"
        );
        parameterHash = requireText(parameterHash, "parameterHash", 128);
        parameterSchemaHash = requireText(
            parameterSchemaHash,
            "parameterSchemaHash",
            128
        );
        confirmationMessage = requireText(
            confirmationMessage,
            "confirmationMessage",
            1000
        );
        idempotencyKey = requireText(
            idempotencyKey,
            "idempotencyKey",
            200
        );
        evidenceHashes = evidenceHashes == null
            ? List.of()
            : List.copyOf(evidenceHashes);
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException(
                "expiresAt must be after createdAt"
            );
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        protectedOutcome = normalizeOptional(protectedOutcome);
        failureReason = normalizeFailureReason(failureReason);
    }

    public ActionProposalView publicView() {
        return new ActionProposalView(
            receiptId,
            actionName,
            confirmationMessage,
            status,
            createdAt,
            expiresAt
        );
    }

    public ActionProposalReceipt confirmed(Instant now) {
        requireState(ActionProposalReceiptStatus.PROPOSED);
        return transition(
            ActionProposalReceiptStatus.CONFIRMED,
            now,
            now,
            executionStartedAt,
            executedAt,
            terminalAt,
            protectedOutcome,
            failureReason
        );
    }

    public ActionProposalReceipt executing(Instant now) {
        requireState(ActionProposalReceiptStatus.CONFIRMED);
        return transition(
            ActionProposalReceiptStatus.EXECUTING,
            now,
            confirmedAt,
            now,
            executedAt,
            terminalAt,
            protectedOutcome,
            failureReason
        );
    }

    public ActionProposalReceipt rejected(Instant now) {
        requireState(ActionProposalReceiptStatus.PROPOSED);
        return transition(
            ActionProposalReceiptStatus.REJECTED,
            now,
            confirmedAt,
            executionStartedAt,
            executedAt,
            now,
            null,
            "USER_REJECTED"
        );
    }

    public ActionProposalReceipt expired(Instant now) {
        if (status != ActionProposalReceiptStatus.PROPOSED
            && status != ActionProposalReceiptStatus.CONFIRMED) {
            throw new IllegalStateException(
                "Cannot expire receipt from " + status
            );
        }
        return transition(
            ActionProposalReceiptStatus.EXPIRED,
            now,
            confirmedAt,
            executionStartedAt,
            executedAt,
            now,
            null,
            "RECEIPT_EXPIRED"
        );
    }

    public ActionProposalReceipt failedBeforeExecution(
        Instant now,
        String reason
    ) {
        if (status != ActionProposalReceiptStatus.PROPOSED
            && status != ActionProposalReceiptStatus.CONFIRMED) {
            throw new IllegalStateException(
                "Cannot fail receipt before execution from " + status
            );
        }
        return transition(
            ActionProposalReceiptStatus.FAILED,
            now,
            confirmedAt,
            executionStartedAt,
            executedAt,
            now,
            null,
            requireText(reason, "reason", 160)
        );
    }

    public ActionProposalReceipt completed(
        ActionProposalReceiptStatus finalStatus,
        Instant now,
        String protectedOutcome,
        String failureReason
    ) {
        requireState(ActionProposalReceiptStatus.EXECUTING);
        if (finalStatus != ActionProposalReceiptStatus.SUCCEEDED
            && finalStatus != ActionProposalReceiptStatus.FAILED
            && finalStatus != ActionProposalReceiptStatus.OUTCOME_UNKNOWN) {
            throw new IllegalArgumentException(
                "Execution can complete only as SUCCEEDED, FAILED, or OUTCOME_UNKNOWN"
            );
        }
        return transition(
            finalStatus,
            now,
            confirmedAt,
            executionStartedAt,
            now,
            now,
            protectedOutcome,
            normalizeOptional(failureReason)
        );
    }

    public ActionProposalReceipt unknownAfterRecovery(
        Instant now,
        String protectedOutcome
    ) {
        requireState(ActionProposalReceiptStatus.EXECUTING);
        return transition(
            ActionProposalReceiptStatus.OUTCOME_UNKNOWN,
            now,
            confirmedAt,
            executionStartedAt,
            now,
            now,
            protectedOutcome,
            "STALE_EXECUTION_OUTCOME_UNKNOWN"
        );
    }

    public ActionProposalReceipt reconciled(
        ActionProposalReceiptStatus finalStatus,
        Instant now,
        String protectedOutcome,
        String failureReason
    ) {
        requireState(ActionProposalReceiptStatus.OUTCOME_UNKNOWN);
        if (finalStatus != ActionProposalReceiptStatus.SUCCEEDED
            && finalStatus != ActionProposalReceiptStatus.FAILED) {
            throw new IllegalArgumentException(
                "Unknown outcome can reconcile only to SUCCEEDED or FAILED"
            );
        }
        return transition(
            finalStatus,
            now,
            confirmedAt,
            executionStartedAt,
            executedAt != null ? executedAt : now,
            now,
            protectedOutcome,
            normalizeOptional(failureReason)
        );
    }

    private ActionProposalReceipt transition(
        ActionProposalReceiptStatus nextStatus,
        Instant now,
        Instant nextConfirmedAt,
        Instant nextExecutionStartedAt,
        Instant nextExecutedAt,
        Instant nextTerminalAt,
        String nextProtectedOutcome,
        String nextFailureReason
    ) {
        Objects.requireNonNull(now, "transition time is required");
        return new ActionProposalReceipt(
            receiptId,
            invocationId,
            specialistId,
            specialistContentHash,
            effectiveProfileHash,
            principalFingerprint,
            subjectType,
            subjectFingerprint,
            tenantFingerprint,
            deploymentFingerprint,
            actionName,
            protectedParameters,
            parameterHash,
            parameterSchemaHash,
            confirmationMessage,
            idempotencyKey,
            evidenceHashes,
            nextStatus,
            createdAt,
            expiresAt,
            nextConfirmedAt,
            nextExecutionStartedAt,
            nextExecutedAt,
            nextTerminalAt,
            normalizeOptional(nextProtectedOutcome),
            normalizeFailureReason(nextFailureReason),
            now,
            version + 1
        );
    }

    private void requireState(ActionProposalReceiptStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                "Expected receipt status " + expected + " but was " + status
            );
        }
    }

    @Override
    public String toString() {
        return "ActionProposalReceipt[receiptId=%s, specialistId=%s, actionName=%s, status=%s, version=%d]"
            .formatted(receiptId, specialistId, actionName, status, version);
    }

    private static String requireText(String value, String field) {
        return requireText(value, field, Integer.MAX_VALUE);
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

    private static String normalizeFailureReason(String value) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            return null;
        }
        if (normalized.length() > 160) {
            throw new IllegalArgumentException(
                "failureReason must not exceed 160 characters"
            );
        }
        return normalized;
    }
}
