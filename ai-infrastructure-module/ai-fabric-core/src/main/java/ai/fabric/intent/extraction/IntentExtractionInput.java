package ai.fabric.intent.extraction;

import ai.fabric.dto.AIChatMessage;
import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.intent.orchestration.OrchestrationAuthContextResolver;
import ai.fabric.intent.orchestration.OrchestrationContext;

import java.util.List;

/**
 * Typed input for intent extraction.
 *
 * <p>Separates the user's actual query (used for validation/post-processing and RAG) from the
 * current user message sent to the LLM (which may include bounded pinned-target context).</p>
 */
public record IntentExtractionInput(
    String userQuery,
    String currentUserMessage,
    List<AIChatMessage> historyMessages,
    AIAccessSubjectContext resolvedAuthContext
) {

    public IntentExtractionInput {
        historyMessages = historyMessages != null ? List.copyOf(historyMessages) : List.of();
    }

    public IntentExtractionInput(
        String userQuery,
        String currentUserMessage,
        List<AIChatMessage> historyMessages
    ) {
        this(userQuery, currentUserMessage, historyMessages, null);
    }

    public AIAccessSubjectContext authContext(OrchestrationContext context) {
        return resolvedAuthContext != null
            ? resolvedAuthContext
            : OrchestrationAuthContextResolver.from(
                context != null ? context : OrchestrationContext.anonymous()
            );
    }

    public void validateIdentity(OrchestrationContext context) {
        if (hasIdentity(resolvedAuthContext)) {
            return;
        }
        OrchestrationContext safeContext =
            context != null ? context : OrchestrationContext.anonymous();
        safeContext.validate();
    }

    private boolean hasIdentity(AIAccessSubjectContext authContext) {
        return authContext != null
            && (
                hasText(authContext.getSubjectId())
                || hasText(authContext.getSessionId())
            );
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
