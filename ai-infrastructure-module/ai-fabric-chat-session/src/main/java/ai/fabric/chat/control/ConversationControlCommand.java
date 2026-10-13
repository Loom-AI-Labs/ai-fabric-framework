package ai.fabric.chat.control;

import org.springframework.util.StringUtils;

/** Idempotent, versioned request to change conversation response ownership. */
public record ConversationControlCommand(
    ConversationScope scope,
    ConversationControlCommandType type,
    long expectedVersion,
    String idempotencyKey,
    String actorRef,
    String reasonCode,
    String handoverId,
    String channelBindingId
) {
    public ConversationControlCommand {
        if (scope == null || type == null) {
            throw new IllegalArgumentException("scope and type are required");
        }
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion cannot be negative");
        }
        if (!StringUtils.hasText(idempotencyKey) || !StringUtils.hasText(actorRef)) {
            throw new IllegalArgumentException("idempotencyKey and actorRef are required");
        }
        idempotencyKey = idempotencyKey.trim();
        actorRef = actorRef.trim();
        reasonCode = trim(reasonCode);
        handoverId = trim(handoverId);
        channelBindingId = trim(channelBindingId);
    }

    private static String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
