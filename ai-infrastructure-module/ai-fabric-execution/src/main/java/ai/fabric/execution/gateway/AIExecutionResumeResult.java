package ai.fabric.execution.gateway;

import java.util.Objects;

/**
 * Result of attempting to resume a waiting invocation.
 */
public record AIExecutionResumeResult<O>(
    AIExecutionResumeStatus status,
    AIExecutionResult<O> executionResult,
    AIExecutionFailure failure
) {
    public AIExecutionResumeResult {
        Objects.requireNonNull(status, "status is required");
        boolean successful = status == AIExecutionResumeStatus.RESUMED
            || status == AIExecutionResumeStatus.REPLAYED;
        if (successful && executionResult == null) {
            throw new IllegalArgumentException(
                "Successful resume requires an execution result"
            );
        }
        if (successful && failure != null) {
            throw new IllegalArgumentException(
                "Successful resume must not contain a failure"
            );
        }
        if (!successful && executionResult != null) {
            throw new IllegalArgumentException(
                "Rejected resume must not expose an execution result"
            );
        }
        if (!successful && failure == null) {
            throw new IllegalArgumentException(
                "Rejected resume requires a failure"
            );
        }
    }

    public static <O> AIExecutionResumeResult<O> resumed(
        AIExecutionResult<O> result
    ) {
        return new AIExecutionResumeResult<>(
            AIExecutionResumeStatus.RESUMED,
            result,
            null
        );
    }

    public static <O> AIExecutionResumeResult<O> replayed(
        AIExecutionResult<O> result
    ) {
        return new AIExecutionResumeResult<>(
            AIExecutionResumeStatus.REPLAYED,
            result,
            null
        );
    }

    public static <O> AIExecutionResumeResult<O> rejected(
        AIExecutionResumeStatus status,
        String reason,
        String publicMessage,
        boolean retryable
    ) {
        return new AIExecutionResumeResult<>(
            status,
            null,
            new AIExecutionFailure(reason, publicMessage, retryable)
        );
    }
}
