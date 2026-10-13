package ai.fabric.chat.channel;

import ai.fabric.chat.control.ConversationParticipantRole;
import java.time.Instant;
import java.util.List;
import org.springframework.util.StringUtils;

/** Bounded, provider-safe transcript message projected to a conversation channel. */
public record ConversationChannelMessage(
    String messageId,
    ConversationParticipantRole role,
    String actorRef,
    String safeActorDisplayName,
    String text,
    List<String> attachmentRefs,
    Instant createdAt
) {
    public ConversationChannelMessage {
        if (!StringUtils.hasText(messageId) || role == null
            || !StringUtils.hasText(actorRef) || !StringUtils.hasText(text)
            || createdAt == null) {
            throw new IllegalArgumentException(
                "channel message identity, role, actor, text and time are required"
            );
        }
        messageId = messageId.trim();
        actorRef = actorRef.trim();
        safeActorDisplayName = StringUtils.hasText(safeActorDisplayName)
            ? safeActorDisplayName.trim()
            : null;
        text = text.trim();
        attachmentRefs = attachmentRefs == null ? List.of() : List.copyOf(attachmentRefs);
    }
}
