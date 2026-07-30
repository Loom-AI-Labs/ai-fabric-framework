package ai.fabric.execution.manager;

import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.ConversationBinding;
import java.time.Instant;
import java.util.Objects;

/**
 * Latest-message-only application request for one manager-owned turn.
 */
public record ConversationManagerTurnRequest<I>(
    ConversationManagerId managerId,
    I input,
    TrustedExecutionContext trustedExecutionContext,
    ConversationBinding conversationBinding,
    Instant deadline,
    String idempotencyKey
) {
    public ConversationManagerTurnRequest {
        Objects.requireNonNull(managerId, "managerId is required");
        Objects.requireNonNull(input, "input is required");
        Objects.requireNonNull(
            trustedExecutionContext,
            "trustedExecutionContext is required"
        );
        idempotencyKey = normalizeOptional(idempotencyKey);
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
