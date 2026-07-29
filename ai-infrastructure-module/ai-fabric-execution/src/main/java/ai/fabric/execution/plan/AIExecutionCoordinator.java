package ai.fabric.execution.plan;

import ai.fabric.execution.context.TrustedExecutionContext;
import java.util.Optional;

/**
 * Deterministic coordinator for registered fixed sequential specialist plans.
 */
public interface AIExecutionCoordinator {

    <I, O> PlanExecutionResult<O> execute(
        PlanExecutionRequest<I> request
    );

    <O> PlanExecutionResumeResult<O> resume(
        PlanExecutionResumeRequest request
    );

    Optional<PlanExecutionSnapshot> find(
        String executionId,
        TrustedExecutionContext trustedExecutionContext
    );

    boolean cancel(
        String executionId,
        TrustedExecutionContext trustedExecutionContext
    );
}
