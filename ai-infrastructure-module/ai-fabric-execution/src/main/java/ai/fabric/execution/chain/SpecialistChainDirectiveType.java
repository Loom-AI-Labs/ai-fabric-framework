package ai.fabric.execution.chain;

/** Closed set of model-proposed moves understood by the chain runtime. */
public enum SpecialistChainDirectiveType {
    ASK_USER,
    INVOKE_ONE,
    INVOKE_PARALLEL,
    HANDOFF,
    COMPLETE
}
