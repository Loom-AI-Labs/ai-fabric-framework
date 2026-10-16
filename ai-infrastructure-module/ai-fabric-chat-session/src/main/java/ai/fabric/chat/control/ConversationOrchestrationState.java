package ai.fabric.chat.control;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Durable framework-owned state for one canonical conversation. */
public record ConversationOrchestrationState(
    ConversationScope scope,
    long revision,
    Map<String, Object> metadata,
    Instant updatedAt
) {
    public ConversationOrchestrationState {
        Objects.requireNonNull(scope, "scope is required");
        if (revision < 0) {
            throw new IllegalArgumentException("revision cannot be negative");
        }
        metadata = metadata == null || metadata.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        Objects.requireNonNull(updatedAt, "updatedAt is required");
    }

    @Override
    public Map<String, Object> metadata() {
        return metadata.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
