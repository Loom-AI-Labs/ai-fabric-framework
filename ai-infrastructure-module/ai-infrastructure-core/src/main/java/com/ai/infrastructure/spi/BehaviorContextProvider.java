package com.ai.infrastructure.spi;

import com.ai.infrastructure.intent.orchestration.OrchestrationContext;

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
