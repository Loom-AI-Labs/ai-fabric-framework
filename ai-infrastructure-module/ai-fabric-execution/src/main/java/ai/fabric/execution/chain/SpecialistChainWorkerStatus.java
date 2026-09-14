package ai.fabric.execution.chain;

/** Safe status of one delegated or handed-off worker. */
public enum SpecialistChainWorkerStatus {
    SUCCEEDED,
    INVALID,
    DENIED,
    FAILED,
    DEADLINE_EXCEEDED,
    CANCELLED
}
