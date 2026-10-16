package ai.fabric.chat.interception;

import ai.fabric.dto.Intent;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.dto.IntentType;
import ai.fabric.intent.action.PendingAction;
import ai.fabric.intent.action.PendingActionStore;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorParamSupport;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.conversation.ConversationStateStoreAccess;
import java.util.ArrayList;
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
    private List<PendingAction> observedStack;

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
        List<PendingAction> current = ConversationStateStoreAccess.pendingStack(
            pendingActionStore,
            pipelineContext
        );
        this.observedStack = pendingAction != null
            && !current.isEmpty()
            && pendingAction.equals(current.getFirst())
                ? List.copyOf(current)
                : List.of();
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
        return observedStack.isEmpty() ? null : observedStack.getFirst();
    }

    public PendingAction popPending() {
        if (observedStack.isEmpty()) {
            return null;
        }
        List<PendingAction> expectedStack = observedStack;
        PendingAction current = observedStack.getFirst();
        PendingAction removed = ConversationStateStoreAccess.consumePending(
            pendingActionStore,
            pipelineContext,
            current
        ).orElse(null);
        if (removed == null) {
            throw new IllegalStateException(
                "Pending confirmation stack changed while the interceptor was running"
            );
        }
        observedStack = List.copyOf(expectedStack.subList(1, expectedStack.size()));
        return removed;
    }

    public void pushPending(PendingAction pendingAction) {
        if (pendingAction == null) {
            return;
        }
        List<PendingAction> replacement = new ArrayList<>();
        replacement.add(pendingAction);
        replacement.addAll(observedStack);
        replaceObservedStack(replacement);
    }

    public void replaceTopPending(PendingAction updatedTop) {
        List<PendingAction> observed = pendingStack();
        if (observed.isEmpty()) {
            return;
        }
        List<PendingAction> replacement = new ArrayList<>(observed);
        if (updatedTop == null) {
            replacement.removeFirst();
        } else {
            replacement.set(0, updatedTop);
        }
        replaceObservedStack(replacement);
    }

    public List<PendingAction> pendingStack() {
        return List.copyOf(observedStack);
    }

    public PendingAction previousPending() {
        List<PendingAction> stack = pendingStack();
        return stack.size() > 1 ? stack.get(1) : null;
    }

    public void replacePendingStack(List<PendingAction> stackTopFirst) {
        replaceObservedStack(stackTopFirst);
    }

    private void replaceObservedStack(List<PendingAction> replacementTopFirst) {
        List<PendingAction> expected = observedStack;
        List<PendingAction> replacement = replacementTopFirst == null
            ? List.of()
            : List.copyOf(replacementTopFirst);
        if (!ConversationStateStoreAccess.replaceObservedPendingStack(
            pendingActionStore,
            pipelineContext,
            expected,
            replacement
        )) {
            throw new IllegalStateException(
                "Pending confirmation stack changed while the interceptor was running"
            );
        }
        observedStack = replacement;
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
