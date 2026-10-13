package ai.fabric.chat.channel;

/** Outbound operations understood by a conversation-channel adapter. */
public enum ConversationChannelDispatchType {
    OPEN_HANDOVER,
    USER_MESSAGE,
    CANCEL_HANDOVER,
    RETURN_TO_AI,
    CLOSE
}
