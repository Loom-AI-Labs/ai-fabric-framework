package ai.fabric.execution.action;

public enum ActionProposalReceiptStatus {
    PROPOSED,
    CONFIRMED,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    OUTCOME_UNKNOWN,
    REJECTED,
    EXPIRED;

    public boolean terminal() {
        return this == SUCCEEDED
            || this == FAILED
            || this == OUTCOME_UNKNOWN
            || this == REJECTED
            || this == EXPIRED;
    }
}
