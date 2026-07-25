package ai.fabric.indexing.model;

import ai.fabric.indexing.api.AIProcessOperation;

import java.util.Set;

/**
 * Resolved optional analysis policy.
 */
public record AIAnalysisPolicy(
    boolean enabled,
    Set<AIProcessOperation> after
) {
    public AIAnalysisPolicy {
        after = Set.copyOf(after);
    }

    public static AIAnalysisPolicy disabled() {
        return new AIAnalysisPolicy(false, Set.of());
    }

    public boolean runsAfter(AIProcessOperation operation) {
        return enabled && after.contains(operation);
    }
}
