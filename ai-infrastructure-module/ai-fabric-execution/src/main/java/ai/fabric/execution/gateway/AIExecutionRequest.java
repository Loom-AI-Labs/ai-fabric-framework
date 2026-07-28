package ai.fabric.execution.gateway;

import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.specialist.SpecialistId;
import java.time.Instant;
import java.util.Objects;

/**
 * Typed request to a closed, application-selected specialist.
 */
public record AIExecutionRequest<I>(
    SpecialistId specialistId,
    I input,
    TrustedExecutionContext trustedExecutionContext,
    ConversationBinding conversationBinding,
    Instant deadline,
    String idempotencyKey
) {
    public AIExecutionRequest {
        Objects.requireNonNull(specialistId, "specialistId is required");
        Objects.requireNonNull(input, "input is required");
        Objects.requireNonNull(
            trustedExecutionContext,
            "trustedExecutionContext is required"
        );
        idempotencyKey = normalizeOptional(idempotencyKey);
    }

    public static <I> AIExecutionRequest<I> synchronous(
        SpecialistId specialistId,
        I input,
        TrustedExecutionContext trustedExecutionContext
    ) {
        return new AIExecutionRequest<>(
            specialistId,
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
