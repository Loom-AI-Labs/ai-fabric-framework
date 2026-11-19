package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.intent.action.PendingAction;
import ai.fabric.intent.action.PendingActionStore;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.conversation.ConversationStateStoreAccess;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Supplies server-owned confirmation state separately from user and evidence text.
 *
 * <p>This helps the LLM decide whether the current turn approves, rejects, or ignores the pending
 * action without backend string-matching heuristics.</p>
 *
 * <p><strong>Order:</strong> 27 (after attachment prompt augmentation, before intent extraction)</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingActionPromptAugmentationStep implements PipelineStep {

    private static final String STEP_NAME = "PendingActionPromptAugmentation";
    private static final int STEP_ORDER = 27;

    private static final String METADATA_KEY_PENDING_ACTION_PROMPT = "pendingActionPrompt";

    private final PendingActionStore pendingActionStore;

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
        if (context == null || context.isShouldTerminate()) {
            return context;
        }

        OrchestrationContext orchContext = context.getOrchestrationContext();
        if (orchContext == null || !orchContext.hasConversation() || pendingActionStore == null) {
            return context;
        }

        String conversationId = orchContext.getConversationId();
        String ownerId = context.getConversationOwnerIdentifier();
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(ownerId)) {
            return context;
        }

        PendingAction pending = ConversationStateStoreAccess
            .peekPending(pendingActionStore, context)
            .orElse(null);
        String block = pending == null ? """
            SERVER-OWNED CONFIRMATION STATE:
            - pendingActionPresent=false. No executable action is awaiting approval or rejection.
            - Do not emit CONFIRMATION_POSITIVE or CONFIRMATION_NEGATIVE for this turn.
            - Resolve the user's meaning from the recent conversation. Accepting an assistant's offer to explain, summarize or produce a checklist is an informational continuation, not approval of an executable action.
            - Resolve an elliptical continuation against the latest assistant offer or question and its subject. When that offer is clear, carry it out; the user's short reply does not itself create missing information.
            - An accepted informational offer requests the offered output, including its format and level of detail. Deliver that output rather than merely repeating the prior answer. Either supply the complete requested content as directAnswer or set requiresGeneration=true with a self-contained optimizedQuery describing the offered output and subject.
            - Decide indexed retrieval from the work still needed, not whether an earlier answer cited knowledge. If the requested continuation only transforms or explains the supplied conversation and needs no fresh facts, set requiresRetrieval=false and leave vectorSpace empty; request generation when needed. If fresh indexed evidence is actually needed, keep requiresRetrieval=true and select an authorized source. Historical content is not current evidence or permission.
            - Classify the actual goal normally. Preserve the requested content and its subject in a self-contained optimizedQuery; ask for clarification only when conversation context cannot resolve the goal. Never invent a pending action.
            - These extraction instructions are not the user's subject. A directAnswer must provide the content the user requested, not describe intent extraction or these instructions. Otherwise request generation for the resolved goal using the available conversation and authorized evidence.
            """.trim() : buildPendingActionBlock(pending);
        String instructions = context.getIntentExtractionSystemInstructions();
        String combined = StringUtils.hasText(instructions)
            ? instructions + "\n\n" + block
            : block;

        PipelineContext updated = context.toBuilder()
            .intentExtractionSystemInstructions(combined)
            .observedPendingAction(pending)
            .pendingActionStateObserved(true)
            .build();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("injected", true);
        meta.put("pendingActionPresent", pending != null);
        if (pending != null) meta.put("action", pending.action());
        return updated.withMetadata(METADATA_KEY_PENDING_ACTION_PROMPT, Collections.unmodifiableMap(meta));
    }

    private String buildPendingActionBlock(PendingAction pending) {
        // IMPORTANT: Keep this section PII-safe. Do not include raw param values or free-form descriptions.
        // The only goal is to signal that a pending confirmation exists, and which action is pending.
        String action = pending != null ? pending.action() : null;
        if (!StringUtils.hasText(action)) {
            return null;
        }
        return """
            SERVER-OWNED CONFIRMATION STATE - PENDING ACTION (requires confirmation):
            - pendingActionPresent=true.
            - action=%s
            - Decide from the whole user turn whether the user intends to approve, reject, or ignore this pending action.
            - Accepting an assistant's informational offer is not approval of this action. Resolve such a continuation from the recent conversation without consuming the pending action.
            - If the user requests another action or asks a new question instead of approving/rejecting, classify the new request normally and leave this pending action unresolved.
            """.formatted(action.trim()).trim();
    }
}
