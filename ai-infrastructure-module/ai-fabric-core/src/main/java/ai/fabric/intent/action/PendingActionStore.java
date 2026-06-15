package ai.fabric.intent.action;

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

    /**
     * Peek current pending action (stack-compatible).
     */
    default Optional<PendingAction> peekPendingAction(String conversationId, String ownerId) {
        return getPendingAction(conversationId, ownerId);
    }

    /**
     * Push a pending action (stack-compatible).
     */
    default void pushPendingAction(String conversationId, String ownerId, PendingAction pendingAction) {
        savePendingAction(conversationId, ownerId, pendingAction);
    }

    /**
     * Pop current pending action and return it (stack-compatible).
     */
    default Optional<PendingAction> popPendingAction(String conversationId, String ownerId) {
        Optional<PendingAction> current = getPendingAction(conversationId, ownerId);
        clearPendingAction(conversationId, ownerId);
        return current;
    }

    /**
     * Clear all pending actions (stack-compatible).
     */
    default void clearPendingActions(String conversationId, String ownerId) {
        clearPendingAction(conversationId, ownerId);
    }

    /**
     * Return the pending confirmation stack in top-first order.
     */
    default List<PendingAction> getPendingActionStack(String conversationId, String ownerId) {
        return peekPendingAction(conversationId, ownerId)
            .map(List::of)
            .orElse(List.of());
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
}
