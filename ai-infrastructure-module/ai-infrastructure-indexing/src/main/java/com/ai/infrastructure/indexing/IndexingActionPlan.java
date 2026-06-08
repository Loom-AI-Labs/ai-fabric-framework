package com.ai.infrastructure.indexing;

/**
 * Flags that describe the concrete work to execute for a queue entry.
 */
public record IndexingActionPlan(
    boolean generateEmbedding,
    boolean indexForSearch,
    boolean enableAnalysis,
    boolean removeFromSearch,
    boolean cleanupEmbeddings
) {

    public boolean requiresWork() {
        return generateEmbedding
            || indexForSearch
            || enableAnalysis
            || removeFromSearch
            || cleanupEmbeddings;
    }
}
