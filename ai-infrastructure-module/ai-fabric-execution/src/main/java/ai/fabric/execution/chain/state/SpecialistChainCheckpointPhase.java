package ai.fabric.execution.chain.state;

/** Durable safe point or explicitly uncertain in-flight chain phase. */
public enum SpecialistChainCheckpointPhase {
    READY_FOR_MANAGER,
    MANAGER_IN_FLIGHT,
    DIRECTIVE_ACCEPTED,
    WORKERS_IN_FLIGHT,
    HANDOFF_IN_FLIGHT
}
