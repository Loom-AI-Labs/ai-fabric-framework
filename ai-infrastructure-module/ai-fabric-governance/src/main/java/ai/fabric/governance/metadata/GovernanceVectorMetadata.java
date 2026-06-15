package ai.fabric.governance.metadata;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GovernanceVectorMetadata {

    public static final String INDEXED_CREATED_AT = "_indexedCreatedAt";
    public static final String INDEXED_UPDATED_AT = "_indexedUpdatedAt";

    private GovernanceVectorMetadata() {
    }

    public static Map<String, Object> enrichIndexTimestamps(Map<String, Object> metadata, String preservedCreatedAtIso) {
        Map<String, Object> enriched = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        String now = LocalDateTime.now().toString();

        String createdAt = preservedCreatedAtIso;
        if (createdAt == null) {
            Object existing = enriched.get(INDEXED_CREATED_AT);
            createdAt = existing != null ? existing.toString() : null;
        }
        if (createdAt == null) {
            createdAt = now;
        }

        enriched.put(INDEXED_CREATED_AT, createdAt);
        enriched.put(INDEXED_UPDATED_AT, now);
        return enriched;
    }

    public static LocalDateTime readIndexedCreatedAt(Map<String, Object> metadata) {
        return readTimestamp(metadata, INDEXED_CREATED_AT);
    }

    public static LocalDateTime readIndexedUpdatedAt(Map<String, Object> metadata) {
        return readTimestamp(metadata, INDEXED_UPDATED_AT);
    }

    private static LocalDateTime readTimestamp(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object raw = metadata.get(key);
        if (raw == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.toString());
        } catch (Exception ignored) {
            return null;
        }
    }
}

