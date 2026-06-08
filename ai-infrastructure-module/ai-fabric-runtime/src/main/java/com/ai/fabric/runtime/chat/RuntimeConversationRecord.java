package com.ai.fabric.runtime.chat;

import java.time.LocalDateTime;
import java.util.List;

public record RuntimeConversationRecord(
    String id,
    String ownerId,
    String status,
    LocalDateTime createdAt,
    LocalDateTime lastInteractionAt,
    List<RuntimeConversationTurnRecord> turns
) {
    public RuntimeConversationRecord {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }

    public int turnsCount() {
        return turns.size();
    }
}
