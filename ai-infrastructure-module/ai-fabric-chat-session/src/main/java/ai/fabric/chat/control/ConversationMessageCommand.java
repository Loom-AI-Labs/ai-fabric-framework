package ai.fabric.chat.control;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.StringUtils;

/** Idempotent append request for a participant message. */
public record ConversationMessageCommand(
    ConversationScope scope,
    String messageId,
    String clientMessageId,
    ConversationParticipantRole role,
    String actorRef,
    String content,
    Map<String, Object> metadata
) {
    public ConversationMessageCommand {
        if (scope == null || !StringUtils.hasText(messageId) || role == null
            || !StringUtils.hasText(actorRef) || !StringUtils.hasText(content)) {
            throw new IllegalArgumentException("scope, messageId, role, actorRef and content are required");
        }
        messageId = messageId.trim();
        clientMessageId = StringUtils.hasText(clientMessageId) ? clientMessageId.trim() : null;
        actorRef = actorRef.trim();
        content = content.trim();
        metadata = metadata == null || metadata.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
