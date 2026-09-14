package ai.fabric.execution.chain;

/** External status for one bounded chain execution. */
public enum SpecialistChainExecutionStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    ASKED_USER,
    HANDED_OFF,
    INVALID,
    DENIED,
    FAILED,
    DEADLINE_EXCEEDED,
    CANCELLED;

    public boolean terminal() {
        return this != QUEUED && this != RUNNING;
    }

    public boolean succeeded() {
        return this == COMPLETED
            || this == ASKED_USER
            || this == HANDED_OFF;
    }
}
