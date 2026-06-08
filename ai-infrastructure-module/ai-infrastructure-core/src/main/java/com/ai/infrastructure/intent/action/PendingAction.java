package com.ai.infrastructure.intent.action;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * Represents an action that was proposed to the user and is waiting for explicit confirmation.
 */
public record PendingAction(
    String action,
    Map<String, Object> actionParams,
    String description,
    Instant createdAt,
    Map<String, List<String>> trustedEvidenceValuesByKey
) {

    public static final String TRUSTED_EVIDENCE_METADATA_KEY = "__trustedActionEvidenceValuesByKey";

    public PendingAction(
        String action,
        Map<String, Object> actionParams,
        String description,
        Instant createdAt
    ) {
        this(action, actionParams, description, createdAt, Map.of());
    }

    public PendingAction {
        trustedEvidenceValuesByKey = freezeTrustedEvidenceValues(trustedEvidenceValuesByKey);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", action);
        out.put("params", actionParams != null ? actionParams : Collections.emptyMap());
        out.put("description", description);
        out.put("timestamp", createdAt != null ? createdAt.toString() : null);
        out.put("trustedEvidenceValuesByKey", trustedEvidenceValuesByKey != null
            ? trustedEvidenceValuesByKey
            : Collections.emptyMap());
        return Collections.unmodifiableMap(out);
    }

    @SuppressWarnings("unchecked")
    public static PendingAction fromMap(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String action = raw.get("action") != null ? String.valueOf(raw.get("action")) : null;
        String description = raw.get("description") != null ? String.valueOf(raw.get("description")) : null;

        Instant createdAt = null;
        Object createdAtRaw = raw.get("timestamp");
        if (createdAtRaw instanceof String str && StringUtils.hasText(str)) {
            try {
                createdAt = Instant.parse(str.trim());
            } catch (Exception ignored) {
            }
        }

        Map<String, Object> params = null;
        Object paramsRaw = raw.get("params");
        if (paramsRaw instanceof Map<?, ?> map) {
            params = (Map<String, Object>) map;
        }
        Map<String, List<String>> trustedEvidenceValuesByKey = parseTrustedEvidence(raw.get("trustedEvidenceValuesByKey"));

        if (!StringUtils.hasText(action)) {
            return null;
        }
        return new PendingAction(action.trim(), params, description, createdAt, trustedEvidenceValuesByKey);
    }

    private static Map<String, List<String>> parseTrustedEvidence(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry == null || entry.getKey() == null || !StringUtils.hasText(entry.getKey().toString())) {
                continue;
            }
            List<String> values = new ArrayList<>();
            Object valueRaw = entry.getValue();
            if (valueRaw instanceof Iterable<?> iterable) {
                for (Object value : iterable) {
                    if (value != null && StringUtils.hasText(value.toString())) {
                        values.add(value.toString().trim());
                    }
                }
            } else if (valueRaw != null && StringUtils.hasText(valueRaw.toString())) {
                values.add(valueRaw.toString().trim());
            }
            if (!values.isEmpty()) {
                out.put(entry.getKey().toString().trim(), List.copyOf(values));
            }
        }
        return out.isEmpty() ? Map.of() : Collections.unmodifiableMap(out);
    }

    private static Map<String, List<String>> freezeTrustedEvidenceValues(Map<String, List<String>> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
            if (entry == null || !StringUtils.hasText(entry.getKey()) || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            List<String> values = entry.getValue().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
            if (!values.isEmpty()) {
                out.put(entry.getKey().trim(), List.copyOf(values));
            }
        }
        return out.isEmpty() ? Map.of() : Collections.unmodifiableMap(out);
    }
}
