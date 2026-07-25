package ai.fabric.indexing;

/**
 * Lifecycle status for entries inside the indexing queue.
 */
public enum IndexingStatus {
    COMMIT_PENDING,
    PENDING,
    PROCESSING,
    COMPLETED,
    SUPERSEDED,
    DEAD_LETTER
}
