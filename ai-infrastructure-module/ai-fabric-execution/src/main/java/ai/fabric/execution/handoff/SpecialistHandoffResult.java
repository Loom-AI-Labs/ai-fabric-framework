package ai.fabric.execution.handoff;

import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionStatus;
import ai.fabric.execution.specialist.SpecialistId;
import java.time.Instant;
import java.util.Objects;

/**
 * One-level predecessor/successor result with safe execution lineage.
 */
public record SpecialistHandoffResult<P, O>(
    String handoffId,
    String predecessorInvocationId,
    SpecialistId predecessorSpecialistId,
    SpecialistId successorSpecialistId,
    int depth,
    AIExecutionStatus status,
    P predecessorOutput,
    AIExecutionResult<O> successorExecution,
    SpecialistHandoffFailure failure,
    boolean replayed,
    Instant startedAt,
    Instant completedAt
) {
    public SpecialistHandoffResult {
        handoffId = requireText(handoffId, "handoffId");
        predecessorInvocationId = requireText(
            predecessorInvocationId,
            "predecessorInvocationId"
        );
        Objects.requireNonNull(
            predecessorSpecialistId,
            "predecessorSpecialistId is required"
        );
        Objects.requireNonNull(
            successorSpecialistId,
            "successorSpecialistId is required"
        );
        if (depth != 1) {
            throw new IllegalArgumentException(
                "One-level handoff depth must be 1"
            );
        }
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(startedAt, "startedAt is required");
        Objects.requireNonNull(completedAt, "completedAt is required");
        if (status == AIExecutionStatus.SUCCEEDED) {
            if (predecessorOutput == null
                || successorExecution == null
                || !successorExecution.succeeded()
                || failure != null) {
                throw new IllegalArgumentException(
                    "Successful handoff requires validated predecessor and successor results"
                );
            }
        } else if (successorExecution == null && failure == null) {
            throw new IllegalArgumentException(
                "Rejected handoff requires a safe failure"
            );
        }
    }

    public boolean succeeded() {
        return status == AIExecutionStatus.SUCCEEDED;
    }

    public SpecialistHandoffResult<P, O> asReplayed() {
        if (replayed) {
            return this;
        }
        return new SpecialistHandoffResult<>(
            handoffId,
            predecessorInvocationId,
            predecessorSpecialistId,
            successorSpecialistId,
            depth,
            status,
            predecessorOutput,
            successorExecution,
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
