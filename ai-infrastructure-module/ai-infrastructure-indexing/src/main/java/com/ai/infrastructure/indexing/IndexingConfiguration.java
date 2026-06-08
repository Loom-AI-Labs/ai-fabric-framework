package com.ai.infrastructure.indexing;

import com.ai.infrastructure.indexing.api.IndexingStrategy;
import java.util.Objects;

/**
 * Immutable configuration holder for indexing strategies declared via annotations.
 */
public record IndexingConfiguration(
    IndexingStrategy defaultStrategy,
    IndexingStrategy onCreateStrategy,
    IndexingStrategy onUpdateStrategy,
    IndexingStrategy onDeleteStrategy
) {

    public IndexingConfiguration {
        Objects.requireNonNull(defaultStrategy, "defaultStrategy is required");
    }

    /**
     * Resolves the effective strategy for the given operation while honoring an optional override.
     *
     * <p>Resolution order:</p>
     * <ol>
     *     <li>Method-level override (when not {@link IndexingStrategy#AUTO})</li>
     *     <li>Operation-level strategy (create/update/delete) when provided</li>
     *     <li>Entity default strategy</li>
     * </ol>
     *
     * @param operation       lifecycle operation (create/update/delete)
     * @param methodOverride  optional override supplied via {@link com.ai.infrastructure.annotation.AIProcess}
     * @return effective {@link IndexingStrategy}
     */
    public IndexingStrategy resolve(IndexingOperation operation, IndexingStrategy methodOverride) {
        IndexingStrategy candidate = normalize(methodOverride);
        if (candidate != null) {
            return candidate;
        }

        if (operation == IndexingOperation.CREATE) {
            candidate = normalize(onCreateStrategy);
        } else if (operation == IndexingOperation.UPDATE) {
            candidate = normalize(onUpdateStrategy);
        } else if (operation == IndexingOperation.DELETE) {
            candidate = normalize(onDeleteStrategy);
        } else {
            candidate = null;
        }

        if (candidate != null) {
            return candidate;
        }

        return normalize(defaultStrategy, IndexingStrategy.ASYNC);
    }

    private IndexingStrategy normalize(IndexingStrategy strategy) {
        return normalize(strategy, null);
    }

    private IndexingStrategy normalize(IndexingStrategy strategy, IndexingStrategy fallback) {
        if (strategy == null || strategy == IndexingStrategy.AUTO) {
            return fallback;
        }
        return strategy;
    }

    /**
     * Builder entry point.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private IndexingStrategy defaultStrategy = IndexingStrategy.ASYNC;
        private IndexingStrategy onCreateStrategy = IndexingStrategy.AUTO;
        private IndexingStrategy onUpdateStrategy = IndexingStrategy.AUTO;
        private IndexingStrategy onDeleteStrategy = IndexingStrategy.AUTO;

        public Builder defaultStrategy(IndexingStrategy strategy) {
            this.defaultStrategy = strategy;
            return this;
        }

        public Builder onCreateStrategy(IndexingStrategy strategy) {
            this.onCreateStrategy = strategy;
            return this;
        }

        public Builder onUpdateStrategy(IndexingStrategy strategy) {
            this.onUpdateStrategy = strategy;
            return this;
        }

        public Builder onDeleteStrategy(IndexingStrategy strategy) {
            this.onDeleteStrategy = strategy;
            return this;
        }

        public IndexingConfiguration build() {
            return new IndexingConfiguration(
                defaultStrategy,
                onCreateStrategy,
                onUpdateStrategy,
                onDeleteStrategy
            );
        }
    }
}
