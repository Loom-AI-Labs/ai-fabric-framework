package ai.fabric.execution.plan;

import java.util.Objects;

public record PlanExecutionResumeResult<O>(
    PlanExecutionResumeStatus status,
    PlanExecutionResult<O> executionResult,
    PlanExecutionFailure failure
) {
    public PlanExecutionResumeResult {
        Objects.requireNonNull(status, "status is required");
        boolean successful = status == PlanExecutionResumeStatus.RESUMED
            || status == PlanExecutionResumeStatus.REPLAYED;
        if (successful && executionResult == null) {
            throw new IllegalArgumentException(
                "Successful plan resume requires an execution result"
            );
        }
        if (successful && failure != null) {
            throw new IllegalArgumentException(
                "Successful plan resume cannot contain failure"
            );
        }
        if (!successful && executionResult != null) {
            throw new IllegalArgumentException(
                "Rejected plan resume cannot contain an execution result"
            );
        }
        if (!successful && failure == null) {
            throw new IllegalArgumentException(
                "Rejected plan resume requires failure"
            );
        }
    }

    public static <O> PlanExecutionResumeResult<O> resumed(
        PlanExecutionResult<O> result
    ) {
        return new PlanExecutionResumeResult<>(
            PlanExecutionResumeStatus.RESUMED,
            result,
            null
        );
    }

    public static <O> PlanExecutionResumeResult<O> replayed(
        PlanExecutionResult<O> result
    ) {
        return new PlanExecutionResumeResult<>(
            PlanExecutionResumeStatus.REPLAYED,
            result,
            null
        );
    }

    public static <O> PlanExecutionResumeResult<O> rejected(
        PlanExecutionResumeStatus status,
        String reason,
        String publicMessage,
        boolean retryable
    ) {
        return new PlanExecutionResumeResult<>(
            status,
            null,
            new PlanExecutionFailure(
                reason,
                publicMessage,
                retryable,
                null
            )
        );
    }
}
