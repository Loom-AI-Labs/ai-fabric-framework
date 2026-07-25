package ai.fabric.indexing.api;

import java.util.Objects;

/**
 * Safe acknowledgement for an indexing lifecycle request.
 */
public record IndexingOutcome(
    String workId,
    String entityType,
    String entityId,
    AIIndexWorkType workType,
    IndexingStrategy strategy,
    IndexingDispatchStatus status
) {
    public IndexingOutcome {
        workId = Objects.requireNonNull(workId, "workId is required");
        entityType = Objects.requireNonNull(entityType, "entityType is required");
        entityId = Objects.requireNonNull(entityId, "entityId is required");
        Objects.requireNonNull(workType, "workType is required");
        Objects.requireNonNull(strategy, "strategy is required");
        Objects.requireNonNull(status, "status is required");
    }
}
