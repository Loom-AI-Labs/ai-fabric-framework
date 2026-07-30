package ai.fabric.execution.gateway;

/**
 * Canonical synchronous ingress for one backend-owned interactive turn.
 */
@FunctionalInterface
public interface AIInteractiveExecutionGateway {

    <I, O> AIExecutionResult<O> execute(AIExecutionRequest<I> request);
}
