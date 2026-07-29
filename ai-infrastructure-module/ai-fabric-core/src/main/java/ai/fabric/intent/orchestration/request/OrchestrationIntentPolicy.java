package ai.fabric.intent.orchestration.request;

/**
 * Server-owned constraints applied after semantic intent extraction.
 *
 * <p>The model still decides the semantic intent. A non-default policy only
 * constrains execution flags that would contradict an application-declared
 * orchestration contract.</p>
 */
public enum OrchestrationIntentPolicy {
    MODEL_DIRECTED,
    GENERATION_ONLY
}
