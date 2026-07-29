package ai.fabric.execution.specialist.client;

import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.ConversationBinding;
import java.time.Instant;
import java.util.Objects;

/**
 * Caller-owned invocation data for a client already bound to one specialist.
 */
public record SpecialistInvocation<I>(
    I input,
    TrustedExecutionContext trustedExecutionContext,
    ConversationBinding conversationBinding,
    Instant deadline,
    String idempotencyKey
) {
    public SpecialistInvocation {
        Objects.requireNonNull(input, "input is required");
        Objects.requireNonNull(
            trustedExecutionContext,
            "trustedExecutionContext is required"
        );
        idempotencyKey = normalizeOptional(idempotencyKey);
    }

    public static <I> SpecialistInvocation<I> synchronous(
        I input,
        TrustedExecutionContext trustedExecutionContext
    ) {
        return new SpecialistInvocation<>(
            input,
            trustedExecutionContext,
            null,
            null,
            null
        );
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
