package ai.fabric.chat.control;

/** Explicit control transitions supported by the generic conversation lifecycle. */
public enum ConversationControlCommandType {
    REQUEST_HUMAN,
    ACTIVATE_HUMAN,
    CANCEL_HUMAN_REQUEST,
    REQUEST_AI_RESUME,
    COMPLETE_AI_RESUME,
    CLOSE
}
