package ai.fabric.spi;

import ai.fabric.intent.orchestration.OrchestrationContext;

import java.util.Optional;

/**
 * SPI for providing behavior insights without creating a core->behavior dependency.
 */
public interface BehaviorContextProvider {

    /**
     * Fetch behavior context for the given orchestration context.
     * Implementations must accept arbitrary string userIds (UUID not required).
     */
    Optional<BehaviorContext> getBehaviorContext(OrchestrationContext context);
}
