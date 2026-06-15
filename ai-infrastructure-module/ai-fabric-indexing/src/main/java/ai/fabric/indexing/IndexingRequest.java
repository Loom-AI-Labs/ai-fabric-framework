package ai.fabric.indexing;

import ai.fabric.indexing.api.IndexingStrategy;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Immutable request used to enqueue indexing work.
 */
public record IndexingRequest(
    String entityType,
    String entityId,
    String entityClassName,
    IndexingOperation operation,
    IndexingStrategy strategy,
    IndexingActionPlan actionPlan,
    String payload,
    LocalDateTime scheduledFor,
    int maxRetries
) {

    public IndexingRequest {
        Objects.requireNonNull(entityType, "entityType is required");
        Objects.requireNonNull(entityClassName, "entityClassName is required");
        Objects.requireNonNull(operation, "operation is required");
        Objects.requireNonNull(strategy, "strategy is required");
        Objects.requireNonNull(actionPlan, "actionPlan is required");
        Objects.requireNonNull(payload, "payload is required");
        Objects.requireNonNull(scheduledFor, "scheduledFor is required");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String entityType;
        private String entityId;
        private String entityClassName;
        private IndexingOperation operation = IndexingOperation.CREATE;
        private IndexingStrategy strategy = IndexingStrategy.ASYNC;
        private IndexingActionPlan actionPlan = new IndexingActionPlan(true, true, false, false, false);
        private String payload;
        private LocalDateTime scheduledFor = LocalDateTime.now();
        private int maxRetries = 5;

        public Builder entityType(String entityType) {
            this.entityType = entityType;
            return this;
        }

        public Builder entityId(String entityId) {
            this.entityId = entityId;
            return this;
        }

        public Builder entityClassName(String entityClassName) {
            this.entityClassName = entityClassName;
            return this;
        }

        public Builder operation(IndexingOperation operation) {
            this.operation = operation;
            return this;
        }

        public Builder strategy(IndexingStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        public Builder actionPlan(IndexingActionPlan actionPlan) {
            this.actionPlan = actionPlan;
            return this;
        }

        public Builder payload(String payload) {
            this.payload = payload;
            return this;
        }

        public Builder scheduledFor(LocalDateTime scheduledFor) {
            this.scheduledFor = scheduledFor;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public IndexingRequest build() {
            return new IndexingRequest(
                entityType,
                entityId,
                entityClassName,
                operation,
                strategy,
                actionPlan,
                payload,
                scheduledFor != null ? scheduledFor : LocalDateTime.now(),
                maxRetries <= 0 ? 5 : maxRetries
            );
        }
    }
}
