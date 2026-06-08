package com.ai.infrastructure.intent.orchestration;

public enum OrchestrationResultType {
    ACTION_EXECUTED,
    ACTION_DENIED,
    INFORMATION_PROVIDED,
    /**
     * Action requires explicit user confirmation before execution.
     *
     * <p>The action is stored as "pending" in the conversation state. The user must
     * respond with confirmation (e.g., "yes") in a subsequent turn.</p>
     */
    CONFIRMATION_REQUIRED,
    CLARIFICATION_REQUIRED,
    OUT_OF_SCOPE,
    COMPOUND_HANDLED,
    ERROR
}
