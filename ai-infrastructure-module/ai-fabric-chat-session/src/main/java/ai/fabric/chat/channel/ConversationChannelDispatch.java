package ai.fabric.chat.channel;

import ai.fabric.chat.control.ConversationScope;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/** Safe deployment-authored projection sent to a bound human channel. */
public record ConversationChannelDispatch(
    String dispatchId,
    ConversationChannelDispatchType type,
    ConversationScope scope,
    String handoverId,
    String channelBindingId,
    long expectedControlVersion,
    String messageId,
    String text,
    List<String> attachmentRefs,
    List<ConversationChannelMessage> transcript,
    Map<String, Object> consentedIntake,
    Instant createdAt
) {
    public ConversationChannelDispatch {
        if (!StringUtils.hasText(dispatchId) || type == null || scope == null
            || !StringUtils.hasText(handoverId) || !StringUtils.hasText(channelBindingId)
            || createdAt == null) {
            throw new IllegalArgumentException("dispatch identity, type, scope, handover, binding and time are required");
        }
        if (expectedControlVersion < 0) {
            throw new IllegalArgumentException("expectedControlVersion cannot be negative");
        }
        dispatchId = dispatchId.trim();
        handoverId = handoverId.trim();
        channelBindingId = channelBindingId.trim();
        messageId = StringUtils.hasText(messageId) ? messageId.trim() : null;
        text = StringUtils.hasText(text) ? text.trim() : null;
        attachmentRefs = attachmentRefs == null ? List.of() : List.copyOf(attachmentRefs);
        transcript = transcript == null ? List.of() : List.copyOf(transcript);
        consentedIntake = consentedIntake == null || consentedIntake.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(consentedIntake));
    }
}
