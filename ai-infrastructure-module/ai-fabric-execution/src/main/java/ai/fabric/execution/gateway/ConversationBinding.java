package ai.fabric.execution.gateway;

import java.util.Objects;

/**
 * Explicit server-authorized binding to an existing conversation.
 */
public record ConversationBinding(String userId, String conversationId) {
    public ConversationBinding {
        userId = requireText(userId, "userId");
        conversationId = requireText(conversationId, "conversationId");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field + " is required").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
