package ai.fabric.indexing;

import ai.fabric.indexing.api.IndexingStrategy;

/**
 * Queue priority derived from the requested indexing strategy.
 */
public enum IndexingPriority {

    CRITICAL(0),
    HIGH(1),
    STANDARD(5),
    LOW(10);

    private final int weight;

    IndexingPriority(int weight) {
        this.weight = weight;
    }

    public int getWeight() {
        return weight;
    }

    public static IndexingPriority fromStrategy(IndexingStrategy strategy) {
        return switch (strategy) {
            case SYNC -> CRITICAL;
            case ASYNC -> HIGH;
            case BATCH -> LOW;
            case AUTO -> STANDARD;
        };
    }
}
