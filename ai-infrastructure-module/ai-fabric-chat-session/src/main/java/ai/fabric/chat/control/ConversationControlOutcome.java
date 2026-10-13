package ai.fabric.chat.control;

/** Stable outcomes for idempotent conversation-control commands. */
public enum ConversationControlOutcome {
    APPLIED,
    IDEMPOTENT_REPLAY,
    VERSION_CONFLICT,
    INVALID_TRANSITION,
    OWNER_MISMATCH
}
