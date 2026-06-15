package ai.fabric.chat.util;

import ai.fabric.intent.actiondraft.ActionDraft;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * Utility for storing a single action draft inside chat session metadata.
 *
 * <p>State is stored under {@code actionDraft} as a map.</p>
 */
public final class ActionDraftMetadata {

    public static final String METADATA_KEY_DRAFT = "actionDraft";

    private ActionDraftMetadata() {
    }

    @SuppressWarnings("unchecked")
    public static ActionDraft peek(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object raw = metadata.get(METADATA_KEY_DRAFT);
        if (raw instanceof Map<?, ?> map) {
            return ActionDraft.fromMap((Map<String, Object>) map);
        }
        return null;
    }

    public static void put(Map<String, Object> metadata, ActionDraft draft) {
        if (metadata == null || draft == null) {
            return;
        }
        Map<String, Object> map = new LinkedHashMap<>(draft.toMap());
        if (map.isEmpty()) {
            return;
        }
        metadata.put(METADATA_KEY_DRAFT, map);
    }

    public static void clear(Map<String, Object> metadata) {
        if (metadata == null) {
            return;
        }
        metadata.remove(METADATA_KEY_DRAFT);
    }

    public static boolean isSameAction(ActionDraft draft, String actionName) {
        if (draft == null || !StringUtils.hasText(actionName)) {
            return false;
        }
        return actionName.equalsIgnoreCase(draft.action());
    }
}

