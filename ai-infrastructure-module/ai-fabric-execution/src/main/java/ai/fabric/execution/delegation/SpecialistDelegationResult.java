package ai.fabric.execution.delegation;

import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionStatus;
import ai.fabric.execution.specialist.SpecialistId;
import java.time.Instant;
import java.util.Objects;

/**
 * One-level parent/child result with safe execution lineage.
 */
public record SpecialistDelegationResult<P, O>(
    String delegationId,
    String parentInvocationId,
    SpecialistId sourceSpecialistId,
    SpecialistId targetSpecialistId,
    int depth,
    AIExecutionStatus status,
    P sourceOutput,
    AIExecutionResult<O> targetExecution,
    SpecialistDelegationFailure failure,
    boolean replayed,
    Instant startedAt,
    Instant completedAt
) {
    public SpecialistDelegationResult {
        delegationId = requireText(delegationId, "delegationId");
        parentInvocationId = requireText(
            parentInvocationId,
            "parentInvocationId"
        );
        Objects.requireNonNull(
            sourceSpecialistId,
            "sourceSpecialistId is required"
        );
        Objects.requireNonNull(
            targetSpecialistId,
            "targetSpecialistId is required"
        );
        if (depth != 1) {
            throw new IllegalArgumentException(
                "One-level delegation depth must be 1"
            );
        }
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(startedAt, "startedAt is required");
        Objects.requireNonNull(completedAt, "completedAt is required");
        if (status == AIExecutionStatus.SUCCEEDED) {
            if (sourceOutput == null
                || targetExecution == null
                || !targetExecution.succeeded()
                || failure != null) {
                throw new IllegalArgumentException(
                    "Successful delegation requires validated source and target results"
                );
            }
        } else if (targetExecution == null && failure == null) {
            throw new IllegalArgumentException(
                "Rejected delegation requires a safe failure"
            );
        }
    }

    public boolean succeeded() {
        return status == AIExecutionStatus.SUCCEEDED;
    }

    public SpecialistDelegationResult<P, O> asReplayed() {
        if (replayed) {
            return this;
        }
        return new SpecialistDelegationResult<>(
            delegationId,
            parentInvocationId,
            sourceSpecialistId,
            targetSpecialistId,
            depth,
            status,
            sourceOutput,
            targetExecution,
            failure,
            true,
            startedAt,
            completedAt
        );
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
}
