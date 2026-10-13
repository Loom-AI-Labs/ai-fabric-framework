package ai.fabric.chat.control;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.StringUtils;

/** One ordered participant message in the canonical conversation timeline. */
public record ConversationMessage(
    String messageId,
    ConversationScope scope,
    long sequence,
    ConversationParticipantRole role,
    String actorRef,
    String content,
    String clientMessageId,
    Map<String, Object> metadata,
    Instant createdAt
) {
    public ConversationMessage {
        if (!StringUtils.hasText(messageId) || scope == null || sequence < 1 || role == null
            || !StringUtils.hasText(actorRef) || !StringUtils.hasText(content) || createdAt == null) {
            throw new IllegalArgumentException("message identity, scope, role, actor, content and time are required");
        }
        messageId = messageId.trim();
        actorRef = actorRef.trim();
        content = content.trim();
        clientMessageId = StringUtils.hasText(clientMessageId) ? clientMessageId.trim() : null;
        metadata = metadata == null || metadata.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
