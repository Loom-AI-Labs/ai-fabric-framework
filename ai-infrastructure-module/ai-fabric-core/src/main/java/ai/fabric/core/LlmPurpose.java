package ai.fabric.core;

/**
 * Purpose of an LLM request, used to select purpose-specific provider configuration.
 */
public enum LlmPurpose {
    /**
     * Structured outputs and decisioning (intent extraction, classification, planning).
     */
    ORCHESTRATION,

    /**
     * Natural language generation (RAG answers, summaries, narrative).
     */
    GENERATION,

    /**
     * Embedding generation purpose (typically handled by embedding providers, but kept for completeness).
     */
    EMBEDDINGS,

    /**
     * Default purpose, uses global provider configuration.
     */
    DEFAULT
}
