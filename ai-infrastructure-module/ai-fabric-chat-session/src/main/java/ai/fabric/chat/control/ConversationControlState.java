package ai.fabric.chat.control;

/** Identifies who may produce the next response for an active conversation. */
public enum ConversationControlState {
    AI_ACTIVE,
    HUMAN_PENDING,
    HUMAN_ACTIVE,
    AI_RESUME_PENDING,
    CLOSED
}
