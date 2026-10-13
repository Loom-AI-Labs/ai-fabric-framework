package ai.fabric.chat.channel;

/** Normalized inbound events emitted by a conversation-channel provider. */
public enum ConversationChannelEventType {
    HANDOVER_ACCEPTED,
    HANDOVER_CLAIMED,
    HUMAN_MESSAGE,
    HANDOVER_RELEASED,
    HANDOVER_CLOSED,
    DELIVERY_FAILED
}
