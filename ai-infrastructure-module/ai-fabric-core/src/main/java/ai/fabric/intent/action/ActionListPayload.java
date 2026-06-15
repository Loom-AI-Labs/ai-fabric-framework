package ai.fabric.intent.action;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * List/search payload returned from an action.
 *
 * <p>Serialized using reserved list contract keys: {@code _items}, {@code _count} (+ optional
 * {@code _totalCount}, {@code _cursor}). Additional custom keys are allowed via {@link #getExtra()}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ActionListPayload implements ActionPayload {

    @JsonProperty(ActionResultContracts.LIST_COUNT)
    private final int count;

    @JsonProperty(ActionResultContracts.LIST_ITEMS)
    private final List<?> items;

    @JsonProperty(ActionResultContracts.LIST_TOTAL_COUNT)
    private final Long totalCount;

    @JsonProperty(ActionResultContracts.LIST_CURSOR)
    private final String cursor;

    @JsonIgnore
    private final Map<String, Object> extra;

    private ActionListPayload(int count,
                              List<?> items,
                              Long totalCount,
                              String cursor,
                              Map<String, Object> extra) {
        this.count = count;
        this.items = items;
        this.totalCount = totalCount;
        this.cursor = cursor;
        this.extra = extra;
    }

    public static ActionListPayload of(List<?> items) {
        return of(items, Map.of());
    }

    public static ActionListPayload of(List<?> items, Map<String, Object> extra) {
        return of(items, null, null, null, extra);
    }

    /**
     * Build a list payload using the reserved list contract keys and optional pagination metadata.
     *
     * <p>Greenfield: list payloads are explicit contracts. If pagination metadata is present, it must be provided
     * via the reserved keys ({@code _totalCount}, {@code _cursor}) rather than custom domain keys.</p>
     *
     * @param items list items (nullable)
     * @param count count of items (nullable; defaults to {@code items.size()})
     * @param totalCount total number of matches across pages (nullable)
     * @param cursor cursor token for the next page (nullable)
     * @param extra additional non-reserved keys (nullable)
     * @return list payload
     */
    public static ActionListPayload of(List<?> items,
                                      Integer count,
                                      Long totalCount,
                                      String cursor,
                                      Map<String, Object> extra) {
        List<?> safeItems = items != null ? List.copyOf(items) : List.of();
        int effectiveCount = count != null ? count : safeItems.size();
        if (effectiveCount < 0) {
            throw new IllegalArgumentException("count must be >= 0");
        }
        if (effectiveCount != safeItems.size()) {
            throw new IllegalArgumentException("count must equal items.size()");
        }

        Map<String, Object> safeExtra;
        if (extra == null || extra.isEmpty()) {
            safeExtra = Map.of();
        } else {
            Map<String, Object> out = new LinkedHashMap<>();
            extra.forEach((key, value) -> {
                if (key == null) {
                    return;
                }
                String k = key.trim();
                if (k.isEmpty()) {
                    return;
                }
                if (ActionResultContracts.isReservedListKey(k)) {
                    throw new IllegalArgumentException("Key '" + k + "' is reserved by ActionResultContracts.list");
                }
                out.put(k, value);
            });
            safeExtra = Collections.unmodifiableMap(out);
        }

        Long safeTotalCount = totalCount != null && totalCount >= 0 ? totalCount : null;
        String safeCursor = cursor != null && !cursor.trim().isEmpty() ? cursor.trim() : null;
        return new ActionListPayload(effectiveCount, Collections.unmodifiableList(safeItems), safeTotalCount, safeCursor, safeExtra);
    }

    public int getCount() {
        return count;
    }

    public List<?> getItems() {
        return items;
    }

    public Long getTotalCount() {
        return totalCount;
    }

    public String getCursor() {
        return cursor;
    }

    @JsonAnyGetter
    public Map<String, Object> getExtra() {
        return extra;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(ActionResultContracts.LIST_COUNT, count);
        out.put(ActionResultContracts.LIST_ITEMS, items);
        if (totalCount != null) {
            out.put(ActionResultContracts.LIST_TOTAL_COUNT, totalCount);
        }
        if (cursor != null) {
            out.put(ActionResultContracts.LIST_CURSOR, cursor);
        }
        if (extra != null && !extra.isEmpty()) {
            out.putAll(extra);
        }
        return Collections.unmodifiableMap(out);
    }
}
