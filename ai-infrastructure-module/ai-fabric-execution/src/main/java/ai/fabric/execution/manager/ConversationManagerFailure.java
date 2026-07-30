package ai.fabric.execution.manager;

import java.util.Objects;

/**
 * Safe public failure for a bounded conversation-manager turn.
 */
public record ConversationManagerFailure(
    String reason,
    String publicMessage,
    boolean retryable
) {
    public ConversationManagerFailure {
        reason = requireText(reason, "reason");
        publicMessage = requireText(publicMessage, "publicMessage");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
