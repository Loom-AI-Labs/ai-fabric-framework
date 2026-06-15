package ai.fabric.dto;

/**
 * LLM-selected response depth profile for final answer generation.
 *
 * <p>This is advisory, not authoritative business logic. The deployment configuration
 * constrains the token budgets that can be used for each profile.</p>
 */
public enum ResponseGenerationProfile {
    CONCISE,
    STANDARD,
    DEEP
}
