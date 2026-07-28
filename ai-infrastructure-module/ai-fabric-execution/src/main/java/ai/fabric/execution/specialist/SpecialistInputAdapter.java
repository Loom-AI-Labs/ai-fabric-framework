package ai.fabric.execution.specialist;

import ai.fabric.intent.orchestration.OrchestrationContext;

/**
 * Validates typed application input and renders bounded model input deterministically.
 */
public interface SpecialistInputAdapter<I> {

    Class<I> inputType();

    void validate(I input);

    String renderModelInput(I input);

    /**
     * Returns the user-authored text that may be recorded for an explicit conversation binding.
     *
     * <p>The default is no recording. Implementations must not return internal identifiers,
     * specialist instructions, or server-owned context.</p>
     */
    default String conversationInput(I input) {
        return null;
    }

    default OrchestrationContext orchestrationContext(I input) {
        return OrchestrationContext.builder().build();
    }
}
