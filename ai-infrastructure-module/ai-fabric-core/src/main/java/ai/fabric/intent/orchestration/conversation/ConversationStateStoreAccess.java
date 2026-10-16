package ai.fabric.intent.orchestration.conversation;

import ai.fabric.intent.action.PendingAction;
import ai.fabric.intent.action.PendingActionStore;
import ai.fabric.intent.actiondraft.ActionDraft;
import ai.fabric.intent.actiondraft.ActionDraftStore;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import java.util.List;
import java.util.Optional;
import org.springframework.util.StringUtils;

/** Scope-aware access helpers for framework-owned multi-turn orchestration state. */
public final class ConversationStateStoreAccess {

    private ConversationStateStoreAccess() {
    }

    public static Optional<PendingAction> peekPending(PendingActionStore store, PipelineContext context) {
        return ConversationStateScopeResolver.resolve(context)
            .map(store::peekPendingAction)
            .orElseGet(() -> store.peekPendingAction(conversationId(context), ownerId(context)));
    }

    public static void pushPending(PendingActionStore store, PipelineContext context, PendingAction pendingAction) {
        Optional<ConversationStateScope> scope = ConversationStateScopeResolver.resolve(context);
        if (scope.isPresent()) {
            store.pushPendingAction(scope.get(), pendingAction);
        } else {
            store.pushPendingAction(conversationId(context), ownerId(context), pendingAction);
        }
    }

    public static Optional<PendingAction> popPending(PendingActionStore store, PipelineContext context) {
        return ConversationStateScopeResolver.resolve(context)
            .map(store::popPendingAction)
            .orElseGet(() -> store.popPendingAction(conversationId(context), ownerId(context)));
    }

    public static Optional<PendingAction> consumeObservedPending(
        PendingActionStore store,
        PipelineContext context
    ) {
        PendingAction expected = context != null ? context.getObservedPendingAction() : null;
        return consumePending(store, context, expected);
    }

    /** Atomically removes one caller-observed pending action when it is still on top. */
    public static Optional<PendingAction> consumePending(
        PendingActionStore store,
        PipelineContext context,
        PendingAction expected
    ) {
        Optional<ConversationStateScope> scope = ConversationStateScopeResolver.resolve(context);
        if (scope.isPresent()) {
            if (expected == null) {
                return Optional.empty();
            }
            return store.popPendingAction(scope.get(), expected);
        }
        if (expected == null) {
            return store.popPendingAction(conversationId(context), ownerId(context));
        }
        return store.popPendingAction(
            conversationId(context),
            ownerId(context),
            expected
        );
    }

    public static List<PendingAction> pendingStack(PendingActionStore store, PipelineContext context) {
        return ConversationStateScopeResolver.resolve(context)
            .map(store::getPendingActionStack)
            .orElseGet(() -> store.getPendingActionStack(conversationId(context), ownerId(context)));
    }

    public static void replacePendingStack(
        PendingActionStore store,
        PipelineContext context,
        List<PendingAction> stackTopFirst
    ) {
        Optional<ConversationStateScope> scope = ConversationStateScopeResolver.resolve(context);
        if (scope.isPresent()) {
            store.replacePendingActionStack(scope.get(), stackTopFirst);
        } else {
            store.replacePendingActionStack(conversationId(context), ownerId(context), stackTopFirst);
        }
    }

    public static boolean replaceObservedPendingStack(
        PendingActionStore store,
        PipelineContext context,
        List<PendingAction> observedTopFirst,
        List<PendingAction> replacementTopFirst
    ) {
        Optional<ConversationStateScope> scope = ConversationStateScopeResolver.resolve(context);
        if (scope.isPresent()) {
            return store.replacePendingActionStack(
                scope.get(),
                observedTopFirst,
                replacementTopFirst
            );
        }
        return store.replacePendingActionStack(
            conversationId(context),
            ownerId(context),
            observedTopFirst,
            replacementTopFirst
        );
    }

    public static Optional<ActionDraft> peekDraft(ActionDraftStore store, PipelineContext context) {
        return ConversationStateScopeResolver.resolve(context)
            .map(store::peekDraft)
            .orElseGet(() -> store.peekDraft(conversationId(context), ownerId(context)));
    }

    public static void saveDraft(ActionDraftStore store, PipelineContext context, ActionDraft draft) {
        Optional<ConversationStateScope> scope = ConversationStateScopeResolver.resolve(context);
        if (scope.isPresent()) {
            store.saveDraft(scope.get(), draft);
        } else {
            store.saveDraft(conversationId(context), ownerId(context), draft);
        }
    }

    public static Optional<ActionDraft> popDraft(ActionDraftStore store, PipelineContext context) {
        return ConversationStateScopeResolver.resolve(context)
            .map(store::popDraft)
            .orElseGet(() -> store.popDraft(conversationId(context), ownerId(context)));
    }

    public static void clearDrafts(ActionDraftStore store, PipelineContext context) {
        Optional<ConversationStateScope> scope = ConversationStateScopeResolver.resolve(context);
        if (scope.isPresent()) {
            store.clearDrafts(scope.get());
        } else {
            store.clearDrafts(conversationId(context), ownerId(context));
        }
    }

    private static String conversationId(PipelineContext context) {
        if (context == null
            || context.getOrchestrationContext() == null
            || !StringUtils.hasText(context.getOrchestrationContext().getConversationId())) {
            throw new IllegalArgumentException("conversationId is required for conversation state");
        }
        return context.getOrchestrationContext().getConversationId();
    }

    private static String ownerId(PipelineContext context) {
        if (context == null || !StringUtils.hasText(context.getConversationOwnerIdentifier())) {
            throw new IllegalArgumentException("ownerId is required for conversation state");
        }
        return context.getConversationOwnerIdentifier();
    }
}
