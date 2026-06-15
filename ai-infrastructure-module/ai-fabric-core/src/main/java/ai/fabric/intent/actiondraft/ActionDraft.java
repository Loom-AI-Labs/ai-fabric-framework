package ai.fabric.intent.actiondraft;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * Represents an incomplete action request that is awaiting missing required parameters.
 */
public record ActionDraft(
    String action,
    Map<String, Object> params,
    String missingSummary,
    Instant createdAt,
    Instant updatedAt
) {

    public ActionDraft {
        if (params == null) {
            params = Collections.emptyMap();
        } else {
            params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (StringUtils.hasText(action)) {
            out.put("action", action);
        }
        if (params != null && !params.isEmpty()) {
            out.put("params", params);
        }
        if (StringUtils.hasText(missingSummary)) {
            out.put("missingSummary", missingSummary);
        }
        if (createdAt != null) {
            out.put("createdAt", createdAt.toString());
        }
        if (updatedAt != null) {
            out.put("updatedAt", updatedAt.toString());
        }
        return Collections.unmodifiableMap(out);
    }

    @SuppressWarnings("unchecked")
    public static ActionDraft fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }

        String action = map.get("action") instanceof String s ? s : null;

        Map<String, Object> params = Collections.emptyMap();
        Object rawParams = map.get("params");
        if (rawParams instanceof Map<?, ?> raw) {
            Map<String, Object> cleaned = new LinkedHashMap<>();
            raw.forEach((k, v) -> {
                if (k == null || v == null) {
                    return;
                }
                cleaned.put(String.valueOf(k), v);
            });
            params = cleaned.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(cleaned);
        }

        String missingSummary = map.get("missingSummary") instanceof String s ? s : null;

        Instant createdAt = null;
        if (map.get("createdAt") instanceof String s && StringUtils.hasText(s)) {
            try {
                createdAt = Instant.parse(s);
            } catch (Exception ignored) {
            }
        }

        Instant updatedAt = null;
        if (map.get("updatedAt") instanceof String s && StringUtils.hasText(s)) {
            try {
                updatedAt = Instant.parse(s);
            } catch (Exception ignored) {
            }
        }

        return new ActionDraft(action, params, missingSummary, createdAt, updatedAt);
    }
}
