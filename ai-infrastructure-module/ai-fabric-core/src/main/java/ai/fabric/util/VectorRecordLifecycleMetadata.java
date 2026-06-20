package ai.fabric.util;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Shared lifecycle metadata keys used by vector providers that persist records as provider payloads.
 */
public final class VectorRecordLifecycleMetadata {

    public static final String INDEXED_CREATED_AT_KEY = "_indexedCreatedAt";
    public static final String INDEXED_UPDATED_AT_KEY = "_indexedUpdatedAt";

    private VectorRecordLifecycleMetadata() {
    }

    public static Map<String, Object> enrichForStore(Map<String, Object> metadata) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return enrich(metadata, now, null);
    }

    public static Map<String, Object> enrichForUpdate(Map<String, Object> metadata, LocalDateTime createdAtHint) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return enrich(metadata, now, createdAtHint);
    }

    public static Optional<LocalDateTime> readCreatedAt(Map<String, Object> metadata) {
        return readTimestamp(metadata, INDEXED_CREATED_AT_KEY);
    }

    public static Optional<LocalDateTime> readUpdatedAt(Map<String, Object> metadata) {
        return readTimestamp(metadata, INDEXED_UPDATED_AT_KEY);
    }

    public static Optional<LocalDateTime> readTimestamp(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return Optional.empty();
        }
        Object raw = metadata.get(key);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(raw.toString()));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private static Map<String, Object> enrich(Map<String, Object> metadata,
                                              LocalDateTime now,
                                              LocalDateTime createdAtHint) {
        Map<String, Object> enriched = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        if (readCreatedAt(enriched).isEmpty()) {
            LocalDateTime createdAt = createdAtHint != null ? createdAtHint : now;
            enriched.put(INDEXED_CREATED_AT_KEY, createdAt.toString());
        }
        enriched.put(INDEXED_UPDATED_AT_KEY, now.toString());
        return enriched;
    }
}
