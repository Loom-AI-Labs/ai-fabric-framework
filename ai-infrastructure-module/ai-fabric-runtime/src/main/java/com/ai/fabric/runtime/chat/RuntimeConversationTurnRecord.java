package com.ai.fabric.runtime.chat;

import java.time.LocalDateTime;

public record RuntimeConversationTurnRecord(
    LocalDateTime timestamp,
    String userQuery,
    String aiResponse
) {
}
