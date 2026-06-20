package ai.fabric.chat.util;

import ai.fabric.intent.action.PendingAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility for storing a stack of pending confirmation actions inside chat session metadata.
 *
 * <p>State is stored under the {@code confirmationStack} key as a list of maps.</p>
 */
public final class ConfirmationStack {

    public static final String METADATA_KEY_STACK = "confirmationStack";

    private ConfirmationStack() {
    }

    public static PendingAction peek(Map<String, Object> metadata) {
        List<Map<String, Object>> stack = getStack(metadata);
        if (stack.isEmpty()) {
            return null;
        }
        Map<String, Object> top = stack.get(stack.size() - 1);
        return PendingAction.fromMap(top);
    }

    public static PendingAction pop(Map<String, Object> metadata) {
        List<Map<String, Object>> stack = getStack(metadata);
        if (stack.isEmpty()) {
            if (metadata != null && metadata.containsKey(METADATA_KEY_STACK)) {
                metadata.remove(METADATA_KEY_STACK);
            }
            return null;
        }
        Map<String, Object> top = stack.remove(stack.size() - 1);
        persistStack(metadata, stack, Integer.MAX_VALUE);
        return PendingAction.fromMap(top);
    }

    public static void push(Map<String, Object> metadata, PendingAction pendingAction) {
        push(metadata, pendingAction, Integer.MAX_VALUE);
    }

    public static void push(Map<String, Object> metadata, PendingAction pendingAction, int maxDepth) {
        if (metadata == null || pendingAction == null) {
            return;
        }
        List<Map<String, Object>> stack = getStack(metadata);
        stack.add(pendingAction.toMap());
        persistStack(metadata, stack, maxDepth);
    }

    public static void clear(Map<String, Object> metadata) {
        if (metadata == null) {
            return;
        }
        metadata.remove(METADATA_KEY_STACK);
    }

    public static List<PendingAction> getAll(Map<String, Object> metadata) {
        List<Map<String, Object>> stack = getStack(metadata);
        if (stack.isEmpty()) {
            return List.of();
        }
        List<PendingAction> out = new ArrayList<>();
        for (int index = stack.size() - 1; index >= 0; index--) {
            PendingAction pendingAction = PendingAction.fromMap(stack.get(index));
            if (pendingAction != null) {
                out.add(pendingAction);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public static void replace(Map<String, Object> metadata, List<PendingAction> stackTopFirst) {
        replace(metadata, stackTopFirst, Integer.MAX_VALUE);
    }

    public static void replace(Map<String, Object> metadata, List<PendingAction> stackTopFirst, int maxDepth) {
        if (metadata == null) {
            return;
        }
        if (stackTopFirst == null || stackTopFirst.isEmpty()) {
            clear(metadata);
            return;
        }
        List<Map<String, Object>> persisted = new ArrayList<>();
        for (int index = stackTopFirst.size() - 1; index >= 0; index--) {
            PendingAction pendingAction = stackTopFirst.get(index);
            if (pendingAction != null) {
                persisted.add(pendingAction.toMap());
            }
        }
        if (persisted.isEmpty()) {
            clear(metadata);
            return;
        }
        persistStack(metadata, persisted, maxDepth);
    }

    private static List<Map<String, Object>> getStack(Map<String, Object> metadata) {
        if (metadata == null) {
            return new ArrayList<>();
        }
        Object raw = metadata.get(METADATA_KEY_STACK);
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> map) {
                    out.add(copyMap(map));
                }
            }
            return out;
        }
        return new ArrayList<>();
    }

    private static Map<String, Object> copyMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (map == null || map.isEmpty()) {
            return out;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return Collections.unmodifiableMap(out);
    }

    private static void persistStack(Map<String, Object> metadata, List<Map<String, Object>> stack, int maxDepth) {
        if (metadata == null) {
            return;
        }
        if (stack == null || stack.isEmpty()) {
            metadata.remove(METADATA_KEY_STACK);
            return;
        }
        int boundedDepth = maxDepth > 0 ? maxDepth : 1;
        List<Map<String, Object>> bounded = new ArrayList<>(stack);
        while (bounded.size() > boundedDepth) {
            bounded.remove(0);
        }
        metadata.put(METADATA_KEY_STACK, Collections.unmodifiableList(bounded));
    }
}
