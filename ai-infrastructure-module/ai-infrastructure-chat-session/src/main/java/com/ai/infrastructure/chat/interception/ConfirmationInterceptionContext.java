package com.ai.infrastructure.chat.interception;

import com.ai.infrastructure.dto.Intent;
import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.dto.IntentType;
import com.ai.infrastructure.intent.action.PendingAction;
import com.ai.infrastructure.intent.action.PendingActionStore;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * Context passed to {@code @OnPendingActionConfirmation} handlers.
 *
 * <p>This object offers small, safe helpers for operating on the pending-action confirmation stack and for
 * constructing common interception outcomes.</p>
 */
public final class ConfirmationInterceptionContext {

    private final PendingActionStore pendingActionStore;
    private final PipelineContext pipelineContext;
    private final MultiIntentResponse intentResponse;
    private final PendingAction pendingAction;
    private final Intent confirmationIntent;

    public ConfirmationInterceptionContext(PendingActionStore pendingActionStore,
                                           PipelineContext pipelineContext,
                                           MultiIntentResponse intentResponse,
                                           PendingAction pendingAction,
                                           Intent confirmationIntent) {
        this.pendingActionStore = pendingActionStore;
        this.pipelineContext = pipelineContext;
        this.intentResponse = intentResponse;
        this.pendingAction = pendingAction;
        this.confirmationIntent = confirmationIntent;
    }

    public PipelineContext pipelineContext() {
        return pipelineContext;
    }

    public PendingAction pending() {
        return pendingAction;
    }

    public MultiIntentResponse intentResponse() {
        return intentResponse;
    }

    public Intent confirmationIntent() {
        return confirmationIntent;
    }

    public boolean isPositiveConfirmation() {
        return confirmationIntent != null && confirmationIntent.getType() == IntentType.CONFIRMATION_POSITIVE;
    }

    public boolean isNegativeConfirmation() {
        return confirmationIntent != null && confirmationIntent.getType() == IntentType.CONFIRMATION_NEGATIVE;
    }

    public PendingAction peekPending() {
        return pendingActionStore.peekPendingAction(conversationId(), ownerId()).orElse(null);
    }

    public PendingAction popPending() {
        return pendingActionStore.popPendingAction(conversationId(), ownerId()).orElse(null);
    }

    public void pushPending(PendingAction pendingAction) {
        pendingActionStore.pushPendingAction(conversationId(), ownerId(), pendingAction);
    }

    public void replaceTopPending(PendingAction updatedTop) {
        popPending();
        pushPending(updatedTop);
    }

    public List<PendingAction> pendingStack() {
        return pendingActionStore.getPendingActionStack(conversationId(), ownerId());
    }

    public PendingAction previousPending() {
        List<PendingAction> stack = pendingStack();
        return stack.size() > 1 ? stack.get(1) : null;
    }

    public void replacePendingStack(List<PendingAction> stackTopFirst) {
        pendingActionStore.replacePendingActionStack(conversationId(), ownerId(), stackTopFirst);
    }

    /**
     * Sets a boolean guard flag on the current top pending action.
     */
    public void setTopPendingParamTrue(String key) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        PendingAction top = peekPending();
        if (top == null) {
            return;
        }
        Map<String, Object> params = top.actionParams();
        Map<String, Object> next = params != null ? new HashMap<>(params) : new HashMap<>();
        next.put(key, true);
        replaceTopPending(new PendingAction(
            top.action(),
            Map.copyOf(next),
            top.description(),
            top.createdAt(),
            top.trustedEvidenceValuesByKey()
        ));
    }

    public boolean isTopPendingParamTrue(String key) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        PendingAction top = peekPending();
        if (top == null) {
            return false;
        }
        Map<String, Object> params = top.actionParams();
        return params != null && Boolean.TRUE.equals(params.get(key));
    }

    public InterceptionDecision promptAction(String actionName, Map<String, Object> params) {
        Intent action = Intent.builder()
            .type(IntentType.ACTION)
            .action(actionName)
            .actionParams(params != null ? params : Map.of())
            .confidence(1.0d)
            .build();
        return new InterceptionDecision(java.util.List.of(action), Set.of());
    }

    public InterceptionDecision executeAction(String actionName, Map<String, Object> params) {
        Intent action = Intent.builder()
            .type(IntentType.ACTION)
            .action(actionName)
            .actionParams(params != null ? params : Map.of())
            .confidence(1.0d)
            .build();
        return new InterceptionDecision(java.util.List.of(action), Set.of(actionName));
    }

    public InterceptionDecision reply(String message) {
        Intent info = Intent.builder()
            .type(IntentType.INFORMATION)
            .requiresRetrieval(false)
            .requiresGeneration(false)
            .directAnswer(message)
            .confidence(1.0d)
            .build();
        return new InterceptionDecision(java.util.List.of(info), Set.of());
    }

    private String conversationId() {
        return pipelineContext != null && pipelineContext.getOrchestrationContext() != null
            ? pipelineContext.getOrchestrationContext().getConversationId()
            : null;
    }

    private String ownerId() {
        return pipelineContext != null ? pipelineContext.getIdentifier() : null;
    }
}
