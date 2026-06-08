package com.ai.infrastructure.intent.action;

import java.util.Collections;
import java.util.List;

/**
 * Contract that domain services implement to expose actions to the AI orchestrator.
 */
public interface AIActionProvider {

    /**
     * @return ordered list of actions supported by this provider.
     */
    List<ActionInfo> getAvailableActions();

    /**
     * Friendly provider name used for diagnostics.
     */
    default String getProviderName() {
        return getClass().getSimpleName();
    }

    /**
     * Safe helper for providers that wish to return an immutable empty list.
     */
    default List<ActionInfo> noActions() {
        return Collections.emptyList();
    }
}
