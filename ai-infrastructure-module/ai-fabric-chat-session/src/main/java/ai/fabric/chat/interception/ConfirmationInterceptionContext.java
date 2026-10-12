package ai.fabric.chat.interception;

import ai.fabric.dto.Intent;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.dto.IntentType;
import ai.fabric.intent.action.PendingAction;
import ai.fabric.intent.action.PendingActionStore;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorParamSupport;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
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

    public void setTopPendingGuard(String key) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        PendingAction top = peekPending();
        if (top == null) {
            return;
        }
        replaceTopPending(ConfirmationInterceptorParamSupport.withGuardSet(top, key));
    }

    public boolean isTopPendingGuardSet(String key) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        return ConfirmationInterceptorParamSupport.isGuardSet(peekPending(), key);
    }

    /**
     * @deprecated Interceptor guards are internal state, not action parameters.
     */
    @Deprecated(forRemoval = true)
    public void setTopPendingParamTrue(String key) {
        setTopPendingGuard(key);
    }

    /**
     * @deprecated Interceptor guards are internal state, not action parameters.
     */
    @Deprecated(forRemoval = true)
    public boolean isTopPendingParamTrue(String key) {
        return isTopPendingGuardSet(key);
    }

    public InterceptionDecision promptAction(String actionName, Map<String, Object> params) {
        Intent action = Intent.builder()
            .type(IntentType.ACTION)
            .action(actionName)
            .actionParams(params != null ? params : Map.of())
            .confidence(1.0d)
            .requiresRetrieval(false)
            .requiresGeneration(false)
            .requiresTargetResolution(false)
            .needsAdvancedRAG(false)
            .build();
        return new InterceptionDecision(java.util.List.of(action), Set.of());
    }

    public InterceptionDecision executeAction(String actionName, Map<String, Object> params) {
        Intent action = Intent.builder()
            .type(IntentType.ACTION)
            .action(actionName)
            .actionParams(params != null ? params : Map.of())
            .confidence(1.0d)
            .requiresRetrieval(false)
            .requiresGeneration(false)
            .requiresTargetResolution(false)
            .needsAdvancedRAG(false)
            .build();
        return new InterceptionDecision(java.util.List.of(action), Set.of(actionName));
    }

    public InterceptionDecision reply(String message) {
        Intent info = Intent.builder()
            .type(IntentType.INFORMATION)
            .requiresRetrieval(false)
            .requiresGeneration(false)
            .requiresTargetResolution(false)
            .needsAdvancedRAG(false)
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
        return pipelineContext != null
            ? pipelineContext.getConversationOwnerIdentifier()
            : null;
    }
}
