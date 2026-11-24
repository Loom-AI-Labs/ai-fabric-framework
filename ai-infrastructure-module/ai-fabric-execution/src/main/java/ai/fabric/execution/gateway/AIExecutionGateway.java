package ai.fabric.execution.gateway;

import ai.fabric.execution.context.TrustedExecutionContext;
import java.util.Optional;

public interface AIExecutionGateway {

    <I, O> AIExecutionResult<O> execute(AIExecutionRequest<I> request);

    ExecutionHandle submit(AIExecutionRequest<?> request);

    default ExecutionHandle submitGoal(GoalExecutionRequest request) {
        throw new UnsupportedOperationException("DURABLE_GOAL_HOST_UNAVAILABLE");
    }

    default boolean controlGoal(String invocationId, GoalExecutionControl command, TrustedExecutionContext context) { return false; }

    /** Protected host-state access. Never expose parameters/observations in an unfiltered public response. */
    default Optional<ai.fabric.intent.orchestration.workflow.WorkflowCheckpoint> findWorkflowCheckpoint(
        String invocationId, TrustedExecutionContext context) { return Optional.empty(); }

    <O> AIExecutionResumeResult<O> resume(
        AIExecutionResumeRequest request
    );

    Optional<ExecutionSnapshot> find(
        String invocationId,
        TrustedExecutionContext trustedExecutionContext
    );

    boolean cancel(
        String invocationId,
        TrustedExecutionContext trustedExecutionContext
    );
}
