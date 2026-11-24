package ai.fabric.execution.gateway;

import java.util.function.Function;

/** Restores fresh application authority and canonical conversation state on each durable claim. */
public interface DurableGoalExecutionHost {
    /** Validate authority and return persistable input without credentials or a process-local snapshot. */
    AIExecutionRequest<?> prepareSubmission(GoalExecutionRequest request);
    /** Bind verified current authority around execute.apply(restoredRequest), then release host resources. */
    AIExecutionResult<?> executeSegment(GoalExecutionClaim claim, AIExecutionRequest<?> persistedRequest,
        Function<AIExecutionRequest<?>, AIExecutionResult<?>> execute);
    /** Post-commit, at-least-once notification. Canonical finalization must be idempotent by invocation ID. */
    default void executionUpdated(ExecutionSnapshot snapshot, String authorityReference) {}
}
