package ai.fabric.chat.channel;

/** Adapter delivery result retained by the host outbox. */
public record ConversationChannelDeliveryResult(
    boolean accepted,
    String externalConversationRef,
    String providerMessageRef,
    String code,
    String message,
    boolean retryable
) {}
