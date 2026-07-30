package ai.fabric.execution.manager;

/**
 * Deterministic application-owned mapping to one approved worker input.
 */
public interface ConversationManagerTargetInputMapper<P, I> {

    ConversationManagerComponentId id();

    Class<P> managerRequestType();

    Class<I> targetInputType();

    I map(P request);
}
