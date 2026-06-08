package com.ai.infrastructure.intent.action;

import java.util.List;
import java.util.Map;

/**
 * Typed action payload.
 *
 * <p>Greenfield: action results must use a framework payload type (no raw Maps/Objects) so the
 * orchestrator can apply deterministic behaviors without domain-specific heuristics.</p>
 */
public sealed interface ActionPayload permits ActionListPayload, ActionObjectPayload {

    /**
     * Convert the payload to a stable {@code Map<String, Object>} representation.
     *
     * <p>This is used by sanitization and downstream orchestration logic.</p>
     */
    Map<String, Object> toMap();

    /**
     * Build an object payload from arbitrary fields.
     */
    static ActionObjectPayload object(Map<String, Object> fields) {
        return ActionObjectPayload.of(fields);
    }

    /**
     * Build a list payload using the reserved list contract keys.
     */
    static ActionListPayload list(List<?> items) {
        return ActionListPayload.of(items);
    }

    /**
     * Build a list payload using the reserved list contract keys and additional custom keys.
     */
    static ActionListPayload list(List<?> items, Map<String, Object> extra) {
        return ActionListPayload.of(items, extra);
    }
}

