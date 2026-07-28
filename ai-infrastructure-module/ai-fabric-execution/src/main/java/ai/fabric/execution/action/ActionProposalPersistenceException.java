package ai.fabric.execution.action;

/**
 * Safe failure raised when a durable receipt cannot be read or persisted.
 */
public final class ActionProposalPersistenceException
    extends RuntimeException {

    private final String reason;

    public ActionProposalPersistenceException(
        String reason,
        String message,
        Throwable cause
    ) {
        super(message, cause);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
