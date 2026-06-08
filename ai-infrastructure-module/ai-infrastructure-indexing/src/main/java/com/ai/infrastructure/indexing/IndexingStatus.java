package com.ai.infrastructure.indexing;

/**
 * Lifecycle status for entries inside the indexing queue.
 */
public enum IndexingStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    DEAD_LETTER
}
