package ai.fabric.intent.action;

import ai.fabric.intent.orchestration.conversation.ConversationStateScope;
import java.util.List;
import java.util.Optional;

/**
 * Storage abstraction for pending (confirmation-required) actions.
 *
 * <p>Implementations may persist per-conversation pending actions in a database, Redis, etc.</p>
 */
public interface PendingActionStore {

    Optional<PendingAction> getPendingAction(String conversationId, String ownerId);

    void savePendingAction(String conversationId, String ownerId, PendingAction pendingAction);

    void clearPendingAction(String conversationId, String ownerId);

    default Optional<PendingAction> getPendingAction(ConversationStateScope scope) {
        throw canonicalScopeUnavailable();
    }

    default void savePendingAction(ConversationStateScope scope, PendingAction pendingAction) {
        throw canonicalScopeUnavailable();
    }

    default void clearPendingAction(ConversationStateScope scope) {
        throw canonicalScopeUnavailable();
    }

    /**
     * Peek current pending action (stack-compatible).
     */
    default Optional<PendingAction> peekPendingAction(String conversationId, String ownerId) {
        return getPendingAction(conversationId, ownerId);
    }

    default Optional<PendingAction> peekPendingAction(ConversationStateScope scope) {
        return getPendingAction(scope);
    }

    /**
     * Push a pending action (stack-compatible).
     */
    default void pushPendingAction(String conversationId, String ownerId, PendingAction pendingAction) {
        savePendingAction(conversationId, ownerId, pendingAction);
    }

    default void pushPendingAction(ConversationStateScope scope, PendingAction pendingAction) {
        savePendingAction(scope, pendingAction);
    }

    /**
     * Pop current pending action and return it (stack-compatible).
     */
    default Optional<PendingAction> popPendingAction(String conversationId, String ownerId) {
        Optional<PendingAction> current = getPendingAction(conversationId, ownerId);
        clearPendingAction(conversationId, ownerId);
        return current;
    }

    /** Atomically removes the expected top action, or returns empty if the stack changed. */
    default Optional<PendingAction> popPendingAction(
        String conversationId,
        String ownerId,
        PendingAction expected
    ) {
        throw atomicLegacyMutationUnavailable();
    }

    default Optional<PendingAction> popPendingAction(ConversationStateScope scope) {
        throw canonicalScopeUnavailable();
    }

    /** Atomically removes the expected top action, or returns empty if the stack changed. */
    default Optional<PendingAction> popPendingAction(
        ConversationStateScope scope,
        PendingAction expected
    ) {
        throw canonicalScopeUnavailable();
    }

    /**
     * Clear all pending actions (stack-compatible).
     */
    default void clearPendingActions(String conversationId, String ownerId) {
        clearPendingAction(conversationId, ownerId);
    }

    default void clearPendingActions(ConversationStateScope scope) {
        clearPendingAction(scope);
    }

    /**
     * Return the pending confirmation stack in top-first order.
     */
    default List<PendingAction> getPendingActionStack(String conversationId, String ownerId) {
        return peekPendingAction(conversationId, ownerId)
            .map(List::of)
            .orElse(List.of());
    }

    default List<PendingAction> getPendingActionStack(ConversationStateScope scope) {
        throw canonicalScopeUnavailable();
    }

    /**
     * Replace the entire pending confirmation stack using top-first order.
     */
    default void replacePendingActionStack(String conversationId, String ownerId, List<PendingAction> stackTopFirst) {
        clearPendingActions(conversationId, ownerId);
        if (stackTopFirst == null || stackTopFirst.isEmpty()) {
            return;
        }
        for (int index = stackTopFirst.size() - 1; index >= 0; index--) {
            PendingAction pendingAction = stackTopFirst.get(index);
            if (pendingAction != null) {
                pushPendingAction(conversationId, ownerId, pendingAction);
            }
        }
    }

    /** Atomically replaces a legacy stack only when it still equals the observed snapshot. */
    default boolean replacePendingActionStack(
        String conversationId,
        String ownerId,
        List<PendingAction> expectedTopFirst,
        List<PendingAction> replacementTopFirst
    ) {
        throw atomicLegacyMutationUnavailable();
    }

    default void replacePendingActionStack(ConversationStateScope scope, List<PendingAction> stackTopFirst) {
        throw canonicalScopeUnavailable();
    }

    /** Atomically replaces a stack only when it still equals the observed top-first snapshot. */
    default boolean replacePendingActionStack(
        ConversationStateScope scope,
        List<PendingAction> expectedTopFirst,
        List<PendingAction> replacementTopFirst
    ) {
        throw canonicalScopeUnavailable();
    }

    private static IllegalStateException canonicalScopeUnavailable() {
        return new IllegalStateException(
            "Canonical conversation scope requires a scope-aware pending-action store"
        );
    }

    private static IllegalStateException atomicLegacyMutationUnavailable() {
        return new IllegalStateException(
            "This pending-action store must implement atomic expected-action mutations"
        );
    }
}
