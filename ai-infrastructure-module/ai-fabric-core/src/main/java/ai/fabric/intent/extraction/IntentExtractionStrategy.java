package ai.fabric.intent.extraction;

import ai.fabric.intent.orchestration.OrchestrationContext;

/**
 * Strategy for extracting intents from a query.
 */
public interface IntentExtractionStrategy {

    ExtractionAttempt attemptExtract(IntentExtractionInput input, OrchestrationContext context);

    String getStrategyName();
}
