package ai.fabric.execution.manager;

import ai.fabric.execution.gateway.AIExecutionResult;

/**
 * Application-owned safe external text projection for a validated worker.
 */
public interface ConversationManagerTargetResultProjector<P, O> {

    ConversationManagerComponentId id();

    Class<P> managerRequestType();

    Class<O> targetOutputType();

    String project(P request, AIExecutionResult<O> targetExecution);
}
