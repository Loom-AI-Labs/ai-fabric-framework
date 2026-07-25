package ai.fabric.indexing.api;

import java.util.Collection;

/**
 * Marker used when the built-in lifecycle target resolver should be used.
 */
public final class NoAIProcessTargetResolver implements AIProcessTargetResolver {

    @Override
    public Collection<AIProcessTarget> resolve(AIProcessInvocation invocation) {
        throw new AIProcessContractException("No custom AI process target resolver is configured");
    }
}
