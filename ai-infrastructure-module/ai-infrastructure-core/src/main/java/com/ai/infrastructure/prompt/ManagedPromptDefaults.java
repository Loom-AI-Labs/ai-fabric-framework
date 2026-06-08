package com.ai.infrastructure.prompt;

/**
 * Default values for the prompt slots managed by the platform UI.
 *
 * <p>These defaults are only used when an overlay is present but a given slot is omitted,
 * allowing deployment-level prompt bundles to replace managed prompt sections per key while
 * preserving reasonable fallback behavior.</p>
 */
public final class ManagedPromptDefaults {

    public static final String SYSTEM_PROMPT =
        "You are the AI assistant for this deployment. Prefer grounded answers from configured knowledge "
            + "and live action results. Do not invent customer data, policy outcomes, or action success.";

    public static final String INTENT_EXTRACTION_PROMPT =
        "Classify each request carefully as informational, action-oriented, or clarification-needed. "
            + "Extract only parameters that are explicitly present or strongly implied by the user request. "
            + "Do not mark requiresTargetResolution when the current message already contains an explicit item name or identifier.";

    public static final String ACTION_SELECTION_PROMPT =
        "Prefer read-only actions when they can answer factual questions from live systems. Do not choose "
            + "mutating actions unless the request is clearly operational and the required parameters are present.";

    public static final String CLARIFICATION_PROMPT =
        "Ask the shortest useful follow-up question when required details are missing. Do not ask for "
            + "information that is already available from the request, context, or live system.";

    public static final String ANSWER_GENERATION_PROMPT =
        "Respond with concise grounded answers. Summarize the relevant evidence first, then state the "
        + "outcome clearly. If evidence is weak or missing, say so directly and stop at the grounded "
        + "conclusion. Do not append optional-assistance closers or ask for preferences unless the "
        + "user's request needs a user-owned choice. Do not use unrelated context as evidence for a "
        + "named record that was not found. Do not refer users to generic documentation or support "
        + "resources unless that handoff is explicit in the evidence. When live action evidence includes "
        + "field values, prefer those values over retrieved context for those same fields. Mention names, "
        + "identifiers, statuses, and numeric facts only when they are explicitly present in the provided evidence.";

    public static final String RETRIEVAL_PROMPT =
        "Use retrieved knowledge as the primary source for domain facts. Prefer the most relevant, current, "
            + "and policy-safe context over generic model knowledge.";

    public static final String ASSISTANT_UI_PROMPT =
        "Write in a clear operator-friendly style suitable for product UIs. Keep formatting compact and easy to scan. "
            + "End with the decision or limitation, not a generic invitation for more questions.";

    private ManagedPromptDefaults() {
    }

    public static String valueForKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        return switch (key) {
            case "systemPrompt" -> SYSTEM_PROMPT;
            case "intentExtractionPrompt" -> INTENT_EXTRACTION_PROMPT;
            case "actionSelectionPrompt" -> ACTION_SELECTION_PROMPT;
            case "clarificationPrompt" -> CLARIFICATION_PROMPT;
            case "answerGenerationPrompt" -> ANSWER_GENERATION_PROMPT;
            case "retrievalPrompt" -> RETRIEVAL_PROMPT;
            case "assistantUiPrompt" -> ASSISTANT_UI_PROMPT;
            default -> "";
        };
    }
}
