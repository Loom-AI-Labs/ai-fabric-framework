package ai.fabric.chat.control;

/** Result persisted for command idempotency and safe retry. */
public record ConversationControlResult(
    ConversationControlOutcome outcome,
    ConversationControlSnapshot snapshot,
    String code,
    String message
) {
    public ConversationControlResult {
        if (outcome == null || snapshot == null) {
            throw new IllegalArgumentException("outcome and snapshot are required");
        }
    }

    public boolean applied() {
        return outcome == ConversationControlOutcome.APPLIED
            || outcome == ConversationControlOutcome.IDEMPOTENT_REPLAY;
    }
}
