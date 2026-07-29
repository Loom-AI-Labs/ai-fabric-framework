package ai.fabric.execution.gateway;

import ai.fabric.execution.context.TrustedExecutionContext;
import java.util.Optional;

public interface AIExecutionGateway {

    <I, O> AIExecutionResult<O> execute(AIExecutionRequest<I> request);

    ExecutionHandle submit(AIExecutionRequest<?> request);

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
