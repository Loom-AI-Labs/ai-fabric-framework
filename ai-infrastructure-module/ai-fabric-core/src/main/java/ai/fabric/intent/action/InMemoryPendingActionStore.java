package ai.fabric.intent.action;

import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Deque;
import java.util.List;
import org.springframework.util.StringUtils;

/**
 * Default (non-durable) pending action store.
 */
public class InMemoryPendingActionStore implements PendingActionStore {

    private final ConcurrentMap<String, Deque<PendingAction>> store = new ConcurrentHashMap<>();

    @Override
    public Optional<PendingAction> getPendingAction(String conversationId, String ownerId) {
        return peekPendingAction(conversationId, ownerId);
    }

    @Override
    public void savePendingAction(String conversationId, String ownerId, PendingAction pendingAction) {
        // Legacy API: treat as replace (clear then push).
        clearPendingActions(conversationId, ownerId);
        pushPendingAction(conversationId, ownerId, pendingAction);
    }

    @Override
    public Optional<PendingAction> peekPendingAction(String conversationId, String ownerId) {
        String key = key(conversationId, ownerId);
        if (key == null) {
            return Optional.empty();
        }
        Deque<PendingAction> stack = store.get(key);
        return stack == null ? Optional.empty() : Optional.ofNullable(stack.peek());
    }

    @Override
    public void pushPendingAction(String conversationId, String ownerId, PendingAction pendingAction) {
        String key = key(conversationId, ownerId);
        if (key == null || pendingAction == null) {
            return;
        }
        store.compute(key, (ignored, existing) -> {
            Deque<PendingAction> stack = existing != null ? existing : new ConcurrentLinkedDeque<>();
            stack.push(pendingAction);
            return stack;
        });
    }

    @Override
    public Optional<PendingAction> popPendingAction(String conversationId, String ownerId) {
        String key = key(conversationId, ownerId);
        if (key == null) {
            return Optional.empty();
        }
        Deque<PendingAction> stack = store.get(key);
        if (stack == null) {
            return Optional.empty();
        }
        PendingAction popped = stack.poll();
        if (stack.isEmpty()) {
            store.remove(key);
        }
        return Optional.ofNullable(popped);
    }

    @Override
    public void clearPendingAction(String conversationId, String ownerId) {
        clearPendingActions(conversationId, ownerId);
    }

    @Override
    public void clearPendingActions(String conversationId, String ownerId) {
        String key = key(conversationId, ownerId);
        if (key == null) {
            return;
        }
        store.remove(key);
    }

    @Override
    public List<PendingAction> getPendingActionStack(String conversationId, String ownerId) {
        String key = key(conversationId, ownerId);
        if (key == null) {
            return List.of();
        }
        Deque<PendingAction> stack = store.get(key);
        return stack == null ? List.of() : List.copyOf(stack);
    }

    @Override
    public void replacePendingActionStack(String conversationId, String ownerId, List<PendingAction> stackTopFirst) {
        String key = key(conversationId, ownerId);
        if (key == null) {
            return;
        }
        if (stackTopFirst == null || stackTopFirst.isEmpty()) {
            store.remove(key);
            return;
        }
        Deque<PendingAction> next = new ConcurrentLinkedDeque<>();
        for (int index = stackTopFirst.size() - 1; index >= 0; index--) {
            PendingAction pendingAction = stackTopFirst.get(index);
            if (pendingAction != null) {
                next.push(pendingAction);
            }
        }
        if (next.isEmpty()) {
            store.remove(key);
            return;
        }
        store.put(key, next);
    }

    private String key(String conversationId, String ownerId) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(ownerId)) {
            return null;
        }
        return conversationId.trim() + "|" + ownerId.trim();
    }
}
