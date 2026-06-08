package com.ai.infrastructure.dto;

/**
 * Enumerates the supported intent categories returned by the intent extraction layer.
 */
public enum IntentType {
    /**
     * The user is requesting that the system execute a business action.
     */
    ACTION,

    /**
     * The user is looking for information that may be answered via retrieval.
     */
    INFORMATION,

    /**
     * The user request is outside the supported domain.
     */
    OUT_OF_SCOPE,

    /**
     * The user is positively confirming a previously requested action (e.g., "yes", "confirm", "go ahead").
     */
    CONFIRMATION_POSITIVE,

    /**
     * The user is rejecting/cancelling a previously requested action (e.g., "no", "cancel", "don't do that").
     */
    CONFIRMATION_NEGATIVE
}
