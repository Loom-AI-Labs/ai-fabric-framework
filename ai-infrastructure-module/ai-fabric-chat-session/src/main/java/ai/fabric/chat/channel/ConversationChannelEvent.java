package ai.fabric.chat.channel;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.StringUtils;

/** Signature-verified provider event after adapter normalization. */
public record ConversationChannelEvent(
    String eventId,
    ConversationChannelEventType type,
    String channelBindingId,
    String handoverId,
    String externalConversationRef,
    String externalActorRef,
    String safeActorDisplayName,
    String messageId,
    String text,
    long expectedControlVersion,
    Map<String, Object> metadata,
    Instant occurredAt
) {
    public ConversationChannelEvent {
        if (!StringUtils.hasText(eventId) || type == null
            || !StringUtils.hasText(channelBindingId) || !StringUtils.hasText(handoverId)
            || !StringUtils.hasText(externalConversationRef) || occurredAt == null) {
            throw new IllegalArgumentException("event identity, type, binding, handover, external reference and time are required");
        }
        if (expectedControlVersion < 0) {
            throw new IllegalArgumentException("expectedControlVersion cannot be negative");
        }
        eventId = eventId.trim();
        channelBindingId = channelBindingId.trim();
        handoverId = handoverId.trim();
        externalConversationRef = externalConversationRef.trim();
        externalActorRef = trim(externalActorRef);
        safeActorDisplayName = trim(safeActorDisplayName);
        messageId = trim(messageId);
        text = trim(text);
        metadata = metadata == null || metadata.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    private static String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
