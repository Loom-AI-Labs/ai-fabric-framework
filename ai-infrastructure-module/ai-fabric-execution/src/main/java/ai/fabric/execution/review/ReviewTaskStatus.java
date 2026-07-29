package ai.fabric.execution.review;

public enum ReviewTaskStatus {
    WAITING_FOR_REVIEW,
    DECIDING,
    WAITING_FOR_INFORMATION,
    APPROVED,
    REJECTED,
    CORRECTED,
    ESCALATED,
    EXPIRED,
    FAILED;

    public boolean terminal() {
        return this == APPROVED
            || this == REJECTED
            || this == CORRECTED
            || this == ESCALATED
            || this == EXPIRED
            || this == FAILED;
    }
}
