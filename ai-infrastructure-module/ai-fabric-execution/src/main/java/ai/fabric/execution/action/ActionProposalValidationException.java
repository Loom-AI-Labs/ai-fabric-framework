package ai.fabric.execution.action;

public final class ActionProposalValidationException
    extends RuntimeException {

    private final String reason;

    public ActionProposalValidationException(
        String reason,
        String message
    ) {
        super(message);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        this.reason = reason.trim();
    }

    public String reason() {
        return reason;
    }
}
