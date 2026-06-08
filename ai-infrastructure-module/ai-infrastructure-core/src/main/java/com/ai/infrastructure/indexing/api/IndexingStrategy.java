package com.ai.infrastructure.indexing.api;

/**
 * Defines how and when an entity should be indexed.
 *
 * <p>This enum is part of the core API surface because it is referenced by
 * user-facing annotations (e.g. {@code @AICapable}, {@code @AIProcess}).</p>
 */
public enum IndexingStrategy {

    /**
     * Inherit strategy from the parent configuration level.
     */
    AUTO,

    /**
     * Run indexing synchronously in the same transaction as the caller.
     */
    SYNC,

    /**
     * Enqueue for asynchronous near-real time processing.
     */
    ASYNC,

    /**
     * Enqueue for scheduled batch processing.
     */
    BATCH
}

