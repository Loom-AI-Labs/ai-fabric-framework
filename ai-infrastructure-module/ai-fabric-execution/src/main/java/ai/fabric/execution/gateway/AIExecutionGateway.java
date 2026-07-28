package ai.fabric.execution.gateway;

import java.util.Optional;

public interface AIExecutionGateway {

    <I, O> AIExecutionResult<O> execute(AIExecutionRequest<I> request);

    ExecutionHandle submit(AIExecutionRequest<?> request);

    Optional<ExecutionSnapshot> find(String invocationId);

    boolean cancel(String invocationId);
}
