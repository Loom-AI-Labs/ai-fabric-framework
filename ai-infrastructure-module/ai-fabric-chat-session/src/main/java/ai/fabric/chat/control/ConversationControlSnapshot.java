package ai.fabric.chat.control;

import java.time.Instant;

/** Durable control projection for one conversation. */
public record ConversationControlSnapshot(
    ConversationScope scope,
    ConversationControlState state,
    long version,
    long controlEpoch,
    String activeHandoverId,
    String channelBindingId,
    Instant updatedAt
) {
    public ConversationControlSnapshot {
        if (scope == null || state == null || updatedAt == null) {
            throw new IllegalArgumentException("scope, state and updatedAt are required");
        }
        if (version < 0 || controlEpoch < 0) {
            throw new IllegalArgumentException("version and controlEpoch cannot be negative");
        }
    }
}
