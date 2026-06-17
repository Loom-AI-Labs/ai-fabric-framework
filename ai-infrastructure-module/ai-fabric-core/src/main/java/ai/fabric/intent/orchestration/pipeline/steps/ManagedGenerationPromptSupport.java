package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.prompt.ManagedPromptDefaults;
import ai.fabric.prompt.PromptPreviewOverlaySupport;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Internal helpers for rendering prompts with managed prompt-preview overrides.
 */
final class ManagedGenerationPromptSupport {

    static final String PLACEHOLDER_MANAGED_ANSWER_GENERATION_PROMPT = "managed_answer_generation_prompt";
    static final String PLACEHOLDER_MANAGED_RETRIEVAL_PROMPT = "managed_retrieval_prompt";
    static final String PLACEHOLDER_MANAGED_ASSISTANT_UI_PROMPT = "managed_assistant_ui_prompt";

    private ManagedGenerationPromptSupport() {
    }

    static Map<String, String> extractPromptPreview(PipelineContext pipelineContext) {
        if (pipelineContext == null || pipelineContext.getOrchestrationContext() == null) {
            return Map.of();
        }
        return PromptPreviewOverlaySupport.extract(pipelineContext.getOrchestrationContext().getMetadata());
    }

    static boolean hasManagedGenerationPromptOverride(Map<String, String> promptOverlay) {
        return PromptPreviewOverlaySupport.hasAny(
            promptOverlay,
            "retrievalPrompt",
            "answerGenerationPrompt",
            "assistantUiPrompt"
        );
    }

    static String renderManagedGenerationPrompt(PromptTemplateResolver promptTemplateResolver,
                                                PromptRenderer promptRenderer,
                                                String family,
                                                String templateName,
                                                Map<String, String> placeholders,
                                                Map<String, String> promptOverlay) {
        Map<String, String> values = new LinkedHashMap<>();
        if (placeholders != null && !placeholders.isEmpty()) {
            values.putAll(placeholders);
        }
        values.put(
            PLACEHOLDER_MANAGED_ANSWER_GENERATION_PROMPT,
            PromptPreviewOverlaySupport.resolveOverlayValue(
                promptOverlay,
                "answerGenerationPrompt",
                ManagedPromptDefaults.ANSWER_GENERATION_PROMPT
            )
        );
        values.put(
            PLACEHOLDER_MANAGED_RETRIEVAL_PROMPT,
            PromptPreviewOverlaySupport.resolveOverlayValue(
                promptOverlay,
                "retrievalPrompt",
                ManagedPromptDefaults.RETRIEVAL_PROMPT
            )
        );
        values.put(
            PLACEHOLDER_MANAGED_ASSISTANT_UI_PROMPT,
            PromptPreviewOverlaySupport.resolveOverlayValue(
                promptOverlay,
                "assistantUiPrompt",
                ManagedPromptDefaults.ASSISTANT_UI_PROMPT
            )
        );
        return promptRenderer.render(
            promptTemplateResolver.resolve(family, templateName).template(),
            Collections.unmodifiableMap(values)
        );
    }
}
