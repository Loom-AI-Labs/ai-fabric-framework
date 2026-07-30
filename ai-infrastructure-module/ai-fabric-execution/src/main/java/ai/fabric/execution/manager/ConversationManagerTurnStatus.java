package ai.fabric.execution.manager;

/**
 * Externally meaningful outcome of one bounded manager-owned turn.
 */
public enum ConversationManagerTurnStatus {
    ASKED_USER,
    COMPLETED,
    SPECIALIST_RESULT,
    INVALID,
    DENIED,
    FAILED,
    DEADLINE_EXCEEDED,
    CANCELLED;

    public boolean succeeded() {
        return this == ASKED_USER
            || this == COMPLETED
            || this == SPECIALIST_RESULT;
    }
}
