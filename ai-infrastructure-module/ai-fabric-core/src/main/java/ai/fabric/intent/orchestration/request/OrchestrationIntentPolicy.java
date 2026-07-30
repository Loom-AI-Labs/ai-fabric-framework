package ai.fabric.intent.orchestration.request;

/**
 * Server-owned constraints for the orchestration stage that precedes specialist
 * output projection.
 */
public enum OrchestrationIntentPolicy {
    MODEL_DIRECTED,
    /**
     * Preserves model-selected semantic intent while disabling retrieval for
     * information generation.
     */
    GENERATION_ONLY,

    /**
     * Bypasses semantic intent extraction and ordinary response generation for a
     * closed specialist whose structured-output finalizer is the only model stage.
     *
     * <p>Security, access-control, capability resolution, result normalization,
     * and response sanitization remain active.</p>
     */
    STRUCTURED_OUTPUT_ONLY
}
