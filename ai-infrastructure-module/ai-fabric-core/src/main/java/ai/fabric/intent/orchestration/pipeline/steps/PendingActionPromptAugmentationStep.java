package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.intent.action.PendingAction;
import ai.fabric.intent.action.PendingActionStore;
import ai.fabric.intent.orchestration.OrchestrationContext;
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
 * Injects a bounded "PENDING ACTION" section into the LLM-visible prompt context.
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
        String ownerId = orchContext.getIdentifier();
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(ownerId)) {
            return context;
        }

        PendingAction pending = pendingActionStore.peekPendingAction(conversationId, ownerId).orElse(null);
        if (pending == null || !StringUtils.hasText(pending.action())) {
            return context;
        }

        String block = buildPendingActionBlock(pending);
        if (!StringUtils.hasText(block)) {
            return context;
        }

        String pinnedTargetsContext = context.getPinnedTargetsContext();
        String combined = StringUtils.hasText(pinnedTargetsContext)
            ? pinnedTargetsContext.trim() + "\n\n" + block
            : block;

        PipelineContext updated = context.toBuilder()
            .pinnedTargetsContext(combined)
            .build();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("injected", true);
        meta.put("action", pending.action());
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
            PENDING ACTION (requires confirmation):
            - action=%s
            - Decide from the whole user turn whether the user intends to approve, reject, or ignore this pending action.
            - If the user requests another action or asks a new question instead of approving/rejecting, classify the new request normally and leave this pending action unresolved.
            """.formatted(action.trim()).trim();
    }
}
