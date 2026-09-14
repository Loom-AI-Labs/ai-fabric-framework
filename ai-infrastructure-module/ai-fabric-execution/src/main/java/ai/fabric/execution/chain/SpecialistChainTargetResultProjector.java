package ai.fabric.execution.chain;

import ai.fabric.execution.gateway.AIExecutionResult;

/** Application-owned projection from a worker result into manager-safe data. */
public interface SpecialistChainTargetResultProjector<P, O> {

    SpecialistChainComponentId id();

    Class<P> chainRequestType();

    Class<O> targetOutputType();

    SpecialistChainResultProjection project(
        P chainRequest,
        AIExecutionResult<O> targetExecution
    );
}
