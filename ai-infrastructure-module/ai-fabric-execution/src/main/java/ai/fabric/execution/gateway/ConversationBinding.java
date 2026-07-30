package ai.fabric.execution.gateway;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Objects;

/**
 * Explicit server-authorized binding to an existing conversation.
 */
public record ConversationBinding(
    String userId,
    String conversationId,
    @JsonIgnore String approvedSnapshotToken
) {
    public ConversationBinding {
        userId = requireText(userId, "userId");
        conversationId = requireText(conversationId, "conversationId");
        approvedSnapshotToken = normalizeOptional(approvedSnapshotToken);
    }

    public ConversationBinding(String userId, String conversationId) {
        this(userId, conversationId, null);
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field + " is required").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
