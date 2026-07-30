package ai.fabric.execution.manager;

import ai.fabric.execution.specialist.SpecialistId;
import java.util.Objects;

/**
 * Typed, non-authoritative proposal from one conversation manager.
 */
public record ConversationManagerDirective(
    ConversationManagerDirectiveType type,
    String targetSpecialist,
    String message,
    String reason
) {
    public static final int MAX_MESSAGE_CHARACTERS = 2000;
    public static final int MAX_REASON_CHARACTERS = 500;

    public ConversationManagerDirective {
        Objects.requireNonNull(type, "type is required");
        targetSpecialist = normalizeOptional(targetSpecialist);
        message = normalizeOptional(message);
        reason = requireBounded(
            reason,
            "reason",
            MAX_REASON_CHARACTERS
        );

        if (type == ConversationManagerDirectiveType.INVOKE_SPECIALIST) {
            if (targetSpecialist == null) {
                throw new IllegalArgumentException(
                    "INVOKE_SPECIALIST requires targetSpecialist"
                );
            }
            SpecialistId.parse(targetSpecialist);
            if (message != null) {
                throw new IllegalArgumentException(
                    "INVOKE_SPECIALIST cannot supply a user-facing message"
                );
            }
        } else {
            if (targetSpecialist != null) {
                throw new IllegalArgumentException(
                    type + " cannot supply targetSpecialist"
                );
            }
            message = requireBounded(
                message,
                "message",
                MAX_MESSAGE_CHARACTERS
            );
        }
    }

    public SpecialistId requiredTarget() {
        if (type != ConversationManagerDirectiveType.INVOKE_SPECIALIST) {
            throw new IllegalStateException(
                "Only INVOKE_SPECIALIST has a target"
            );
        }
        return SpecialistId.parse(targetSpecialist);
    }

    private static String requireBounded(
        String value,
        String field,
        int maximum
    ) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(
                field + " must not exceed " + maximum + " characters"
            );
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
