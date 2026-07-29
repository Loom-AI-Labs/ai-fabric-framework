package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.actiondraft.ActionDraft;
import ai.fabric.intent.actiondraft.ActionDraftContinuation;
import ai.fabric.intent.actiondraft.ActionDraftStore;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Gives the intent LLM bounded context about an incomplete action.
 *
 * <p>Parameter values remain server-side. The model receives only the action and
 * public field names needed to interpret a follow-up answer.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActionDraftPromptAugmentationStep implements PipelineStep {

    private static final String STEP_NAME = "ActionDraftPromptAugmentation";
    private static final int STEP_ORDER = 28;
    private static final String METADATA_KEY = "actionDraftPrompt";

    private final ActionDraftStore actionDraftStore;
    private final AIActionRegistry actionRegistry;

    @Override
    public String getStepName() {
        return STEP_NAME;
    }

    @Override
    public int getOrder() {
        return STEP_ORDER;
    }

    @Override
    public PipelineContext process(PipelineContext context) {
        if (context == null
            || context.isShouldTerminate()
            || conversationStateDisabled(context)
            || (context.getMetadata() != null
                && context.getMetadata().containsKey("pendingActionPrompt"))) {
            return context;
        }
        OrchestrationContext orchestrationContext =
            context.getOrchestrationContext();
        if (orchestrationContext == null
            || !orchestrationContext.hasConversation()) {
            return context;
        }
        String conversationId = orchestrationContext.getConversationId();
        String ownerId = context.getConversationOwnerIdentifier();
        if (!StringUtils.hasText(conversationId)
            || !StringUtils.hasText(ownerId)) {
            return context;
        }

        ActionDraft draft;
        try {
            draft = actionDraftStore
                .peekDraft(conversationId, ownerId)
                .orElse(null);
        } catch (RuntimeException ex) {
            log.warn(
                "Failed to load action draft for conversation {}: {}",
                conversationId,
                ex.getMessage()
            );
            return context;
        }
        if (draft == null || !StringUtils.hasText(draft.action())) {
            return context;
        }

        AIActionMetaData metadata = actionRegistry
            .findMetadata(draft.action())
            .orElse(null);
        ActionDraftContinuation continuation =
            ActionDraftContinuationSupport.continuation(draft, metadata);
        if (continuation == null) {
            return context;
        }

        String block = promptBlock(continuation);
        String existingContext =
            context.getIntentExtractionSystemInstructions();
        String combined = StringUtils.hasText(existingContext)
            ? existingContext.trim() + "\n\n" + block
            : block;
        PipelineContext updated = context.toBuilder()
            .actionDraftContinuation(continuation)
            .intentExtractionSystemInstructions(combined)
            .build();

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("injected", true);
        diagnostics.put("action", continuation.action());
        diagnostics.put(
            "collectedParameterNames",
            List.copyOf(continuation.collectedParams().keySet())
        );
        diagnostics.put(
            "missingParameterNames",
            continuation.missingParameters()
        );
        return updated.withMetadata(
            METADATA_KEY,
            Collections.unmodifiableMap(diagnostics)
        );
    }

    private String promptBlock(ActionDraftContinuation continuation) {
        return """
            INCOMPLETE ACTION DRAFT CONTEXT:
            - action=%s
            - previously collected public parameter names=%s
            - still missing public parameter names=%s
            - Before applying normal intent rules, first decide whether the actual current user message continues this draft.
            - Continue this draft only when that message explicitly asks to continue/resume it, or primarily supplies/corrects one or more draft fields.
            - A message that names a missing field and supplies its value continues the draft even if that field name also has a broader domain meaning, or the immediately preceding turn discussed something else.
            - If the message continues the draft, return ACTION for the same action with only values grounded in user messages.
            - Otherwise, classify the message independently. A new question, a request to inspect or explain information, a different action, or a topic change does not continue this draft.
            - Do not output the draft action merely because this context or earlier clarification messages mention it.
            - Do not invent missing values or application-owned identifiers.
            """.formatted(
                oneLine(continuation.action()),
                fieldNames(continuation.collectedParams().keySet().stream().toList()),
                fieldNames(continuation.missingParameters())
            ).trim();
    }

    private String fieldNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "[]";
        }
        return names.stream()
            .filter(StringUtils::hasText)
            .map(this::oneLine)
            .distinct()
            .toList()
            .toString();
    }

    private String oneLine(String value) {
        return value == null
            ? ""
            : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private boolean conversationStateDisabled(PipelineContext context) {
        return context.getOrchestrationRequest() != null
            && context.getOrchestrationRequest().conversationPersistencePolicy()
                == ConversationPersistencePolicy.NEVER;
    }
}
