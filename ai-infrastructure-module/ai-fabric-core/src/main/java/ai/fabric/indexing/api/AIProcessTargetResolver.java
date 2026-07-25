package ai.fabric.indexing.api;

import java.util.Collection;

/**
 * Application extension for resolving lifecycle targets from method invocations.
 */
@FunctionalInterface
public interface AIProcessTargetResolver {

    Collection<AIProcessTarget> resolve(AIProcessInvocation invocation);
}
