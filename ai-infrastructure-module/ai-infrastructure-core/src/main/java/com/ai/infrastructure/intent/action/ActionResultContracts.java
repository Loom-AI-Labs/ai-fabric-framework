package com.ai.infrastructure.intent.action;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Action result payload contracts used by the orchestrator.
 *
 * <p>Greenfield rule: contracts are explicit and domain-agnostic. The orchestrator should not guess
 * domain keys (for example, named business collections). If an action wants the orchestrator to treat its
 * output as a list-style retrieval result, it must use the list contract keys defined here.</p>
 */
public final class ActionResultContracts {

    /**
     * Reserved keys for list-style results.
     *
     * <p>We intentionally prefix reserved keys with an underscore to avoid collisions with
     * domain models that may also expose an {@code items} field.</p>
     */
    public static final String LIST_ITEMS = "_items";
    public static final String LIST_COUNT = "_count";
    public static final String LIST_TOTAL_COUNT = "_totalCount";
    public static final String LIST_CURSOR = "_cursor";

    private static final Set<String> RESERVED_LIST_KEYS = Set.of(
        LIST_ITEMS,
        LIST_COUNT,
        LIST_TOTAL_COUNT,
        LIST_CURSOR
    );

    private ActionResultContracts() {
    }

    /**
     * True when the key is reserved by the list payload contract.
     */
    public static boolean isReservedListKey(String key) {
        return key != null && RESERVED_LIST_KEYS.contains(key);
    }

    /**
     * Build a list-style payload with the reserved list keys.
     */
    public static ActionListPayload list(List<?> items) {
        return ActionListPayload.of(items);
    }

    /**
     * Build a list-style payload with reserved list keys and additional custom keys.
     */
    public static ActionListPayload list(List<?> items, Map<String, Object> extra) {
        return ActionListPayload.of(items, extra);
    }

    /**
     * Build an object payload from arbitrary fields.
     */
    public static ActionObjectPayload object(Map<String, Object> fields) {
        return ActionObjectPayload.of(fields);
    }

    /**
     * Build a list-style payload whose items are explicit entity references.
     *
     * <p>The orchestrator may treat {@link ActionTargetRef} items as pinnable targets for follow-up turns.</p>
     */
    public static ActionListPayload targets(List<ActionTargetRef> items) {
        return ActionListPayload.of(items);
    }
}
