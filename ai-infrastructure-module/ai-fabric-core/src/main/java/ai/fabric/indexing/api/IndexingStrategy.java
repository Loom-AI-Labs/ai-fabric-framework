package ai.fabric.indexing.api;

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
     * Attempt provider work synchronously after the source transaction commits,
     * or immediately when no source transaction is active. Failed work remains
     * durable for retry.
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
