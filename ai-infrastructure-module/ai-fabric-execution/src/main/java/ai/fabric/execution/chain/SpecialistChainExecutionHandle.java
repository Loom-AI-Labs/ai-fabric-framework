package ai.fabric.execution.chain;

import java.time.Instant;
import java.util.Objects;

/** Immediate handle returned for asynchronous chain submission. */
public record SpecialistChainExecutionHandle(
    String executionId,
    SpecialistChainId chainId,
    SpecialistChainExecutionStatus status,
    boolean replayed,
    boolean durable,
    String failureReason,
    Instant submittedAt,
    Instant deadline
) {
    public SpecialistChainExecutionHandle {
        executionId = requireText(executionId, "executionId");
        Objects.requireNonNull(chainId, "chainId is required");
        if (status != SpecialistChainExecutionStatus.QUEUED
            && status != SpecialistChainExecutionStatus.RUNNING
            && !status.terminal()) {
            throw new IllegalArgumentException("Invalid handle status");
        }
        failureReason = normalizeOptional(failureReason);
        if (status.terminal() && !status.succeeded()
            && failureReason == null) {
            throw new IllegalArgumentException(
                "Failed handles require a safe failure reason"
            );
        }
        if ((!status.terminal() || status.succeeded())
            && failureReason != null) {
            throw new IllegalArgumentException(
                "Only failed terminal handles contain a failure reason"
            );
        }
        Objects.requireNonNull(submittedAt, "submittedAt is required");
        Objects.requireNonNull(deadline, "deadline is required");
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
