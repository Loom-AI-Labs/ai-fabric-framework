package ai.fabric.execution.input;

import ai.fabric.execution.gateway.ExecutionDurability;
import ai.fabric.execution.specialist.SpecialistId;
import java.time.Instant;
import java.util.Objects;

/**
 * Safe typed wait outcome exposed to a dialogue owner or host application.
 */
public record NeedsUserInput(
    String requestId,
    String invocationId,
    SpecialistId specialistId,
    String purposeCode,
    String safeQuestion,
    SpecialistInputResponseContract responseContract,
    InputDeliveryTarget deliveryTarget,
    ExecutionDurability durability,
    Instant createdAt,
    Instant expiresAt,
    int maxAttempts
) {
    public NeedsUserInput {
        requestId = requireText(requestId, "requestId");
        invocationId = requireText(invocationId, "invocationId");
        Objects.requireNonNull(specialistId, "specialistId is required");
        purposeCode = requireText(purposeCode, "purposeCode");
        safeQuestion = requireText(safeQuestion, "safeQuestion");
        Objects.requireNonNull(
            responseContract,
            "responseContract is required"
        );
        Objects.requireNonNull(deliveryTarget, "deliveryTarget is required");
        Objects.requireNonNull(durability, "durability is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException(
                "expiresAt must be after createdAt"
            );
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException(
                "maxAttempts must be positive"
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
