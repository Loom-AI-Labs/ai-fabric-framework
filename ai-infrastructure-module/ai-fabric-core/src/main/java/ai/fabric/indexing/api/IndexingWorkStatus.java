package ai.fabric.indexing.api;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Sanitized, read-only projection of one durable indexing work item.
 *
 * <p>This contract intentionally excludes serialized work payloads, result
 * payloads, processing-node ownership, and persistence entities.</p>
 */
public record IndexingWorkStatus(
    String workId,
    String entityType,
    String entityId,
    AIIndexWorkType workType,
    AIProcessOperation sourceOperation,
    IndexingStrategy strategy,
    IndexingWorkState status,
    int retryCount,
    int maxRetries,
    String errorCode,
    String deadLetterReason,
    String correlationId,
    LocalDateTime requestedAt,
    LocalDateTime scheduledFor,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    LocalDateTime lastErrorAt,
    LocalDateTime updatedAt
) {
    public IndexingWorkStatus {
        workId = requireText(workId, "workId");
        entityType = requireText(entityType, "entityType");
        entityId = requireText(entityId, "entityId");
        Objects.requireNonNull(workType, "workType is required");
        Objects.requireNonNull(
            sourceOperation,
            "sourceOperation is required"
        );
        Objects.requireNonNull(strategy, "strategy is required");
        Objects.requireNonNull(status, "status is required");
        if (retryCount < 0) {
            throw new IllegalArgumentException(
                "retryCount must not be negative"
            );
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException(
                "maxRetries must not be negative"
            );
        }
        errorCode = emptyToNull(errorCode);
        deadLetterReason = emptyToNull(deadLetterReason);
        correlationId = emptyToNull(correlationId);
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    public boolean isSuccessfulTerminal() {
        return status.isSuccessfulTerminal();
    }

    public boolean requiresOperatorReview() {
        return status.requiresOperatorReview();
    }

    public boolean isInProgress() {
        return status.isInProgress();
    }

    private static String requireText(String value, String field) {
        String normalized = emptyToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
