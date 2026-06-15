package ai.fabric.intent.orchestration.policy;

import ai.fabric.config.OrchestrationProperties;

/**
 * Optimization profiles providing coherent defaults for orchestration behavior.
 *
 * <p>Profiles are resolved server-side and can be overridden by explicitly configured flags and/or mode overrides.</p>
 */
public enum OrchestrationProfile {
    DEFAULT(
        OrchestrationProperties.InformationMode.LLM_DRIVEN
    ),
    PRODUCTION_NAVIGATOR(
        OrchestrationProperties.InformationMode.DETERMINISTIC_RAG_GENERATE
    ),
    PRODUCTION_CHAT(
        OrchestrationProperties.InformationMode.LLM_DRIVEN
    );

    private final OrchestrationProperties.InformationMode defaultInformationMode;

    OrchestrationProfile(OrchestrationProperties.InformationMode defaultInformationMode) {
        this.defaultInformationMode = defaultInformationMode;
    }

    public OrchestrationProperties.InformationMode defaultInformationMode() {
        return defaultInformationMode;
    }
}
