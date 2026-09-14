package ai.fabric.execution.chain;

import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.ConversationBinding;
import java.time.Instant;
import java.util.Objects;

/** Typed application request for one bounded specialist chain. */
public record SpecialistChainExecutionRequest<I>(
    SpecialistChainId chainId,
    I input,
    TrustedExecutionContext trustedExecutionContext,
    ConversationBinding conversationBinding,
    Instant deadline,
    String idempotencyKey
) {
    public SpecialistChainExecutionRequest {
        Objects.requireNonNull(chainId, "chainId is required");
        Objects.requireNonNull(input, "input is required");
        Objects.requireNonNull(
            trustedExecutionContext,
            "trustedExecutionContext is required"
        );
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.length() > 200) {
            throw new IllegalArgumentException(
                "idempotencyKey must not exceed 200 characters"
            );
        }
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
