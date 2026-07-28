package ai.fabric.intent;

import ai.fabric.intent.orchestration.OrchestrationContext;
import org.springframework.util.StringUtils;

/**
 * Adds application-owned specialist constraints to provider system prompts.
 */
public final class SpecialistPromptInstructionSupport {

    private SpecialistPromptInstructionSupport() {}

    public static String appendToSystemPrompt(
        String basePrompt,
        OrchestrationContext context
    ) {
        String instructions = context != null
            ? context.getSpecialistInstructions()
            : null;
        if (!StringUtils.hasText(instructions)) {
            return basePrompt;
        }
        String base = StringUtils.hasText(basePrompt)
            ? basePrompt.trim() + "\n\n"
            : "";
        return base
            + "APPLICATION-OWNED SPECIALIST CONTRACT\n"
            + instructions.trim();
    }
}
