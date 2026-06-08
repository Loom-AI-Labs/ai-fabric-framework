package com.ai.infrastructure.datasync.normalize;

import com.ai.infrastructure.datasync.AIDataSyncProperties;
import com.ai.infrastructure.dto.AIEntityConfig;
import com.ai.infrastructure.dto.AIMetadataField;
import com.ai.infrastructure.dto.AISearchableField;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Normalizes push-ingested entities into content + metadata suitable for vector indexing.
 *
 * <p>Normalization is deterministic and bounded (fail-closed on oversized payloads).</p>
 */
public class DataSyncEntityNormalizer {

    private static final String META_VECTOR_SPACE = "vectorSpace";

    private final AIDataSyncProperties properties;
    private final ObjectMapper objectMapper;

    public DataSyncEntityNormalizer(AIDataSyncProperties properties, ObjectProvider<ObjectMapper> objectMapperProvider) {
        this.properties = Objects.requireNonNull(properties, "properties is required");
        this.objectMapper = objectMapperProvider != null
            ? objectMapperProvider.getIfAvailable(ObjectMapper::new)
            : new ObjectMapper();
    }

    public NormalizedEntity normalize(AIEntityConfig config,
                                      String vectorSpace,
                                      String id,
                                      String content,
                                      Map<String, Object> entity,
                                      Map<String, Object> metadata) {
        if (config == null) {
            throw new IllegalArgumentException("entity config is required");
        }
        String vs = requireText(vectorSpace, "vectorSpace is required");
        String entityId = requireText(id, "id is required");

        String normalizedContent = normalizeContent(config, content, entity);
        if (normalizedContent.length() > properties.getMaxContentChars()) {
            throw new IllegalArgumentException("Normalized content exceeds maxContentChars=" + properties.getMaxContentChars());
        }

        Map<String, Object> normalizedMetadata = normalizeMetadata(config, vs, entityId, entity, metadata);
        if (properties.getMaxMetadataKeys() > 0 && normalizedMetadata.size() > properties.getMaxMetadataKeys()) {
            throw new IllegalArgumentException("Normalized metadata exceeds maxMetadataKeys=" + properties.getMaxMetadataKeys());
        }

        return new NormalizedEntity(
            normalizedContent,
            normalizedMetadata.isEmpty() ? null : Collections.unmodifiableMap(normalizedMetadata)
        );
    }

    private String normalizeContent(AIEntityConfig config, String content, Map<String, Object> entity) {
        if (StringUtils.hasText(content)) {
            return content.trim();
        }
        if (entity == null || entity.isEmpty()) {
            throw new IllegalArgumentException("Either content or entity must be provided for UPSERT operations.");
        }

        List<AISearchableField> fields = config.getSearchableFields();
        if (fields == null || fields.isEmpty()) {
            return toBoundedString(entity, properties.getMaxContentChars());
        }

        Map<String, Object> byLower = toLowerKeyMap(entity);
        StringBuilder out = new StringBuilder();
        for (AISearchableField field : fields) {
            if (field == null || !StringUtils.hasText(field.getName())) {
                continue;
            }
            String name = field.getName().trim();
            Object raw = byLower.get(name.toLowerCase(Locale.ROOT));
            if (raw == null) {
                continue;
            }
            String value = toBoundedString(raw, properties.getMaxFieldValueChars());
            if (!StringUtils.hasText(value)) {
                continue;
            }
            out.append(name).append(": ").append(value).append("\n");
        }

        String built = out.toString().trim();
        if (StringUtils.hasText(built)) {
            return built;
        }

        return toBoundedString(entity, properties.getMaxContentChars());
    }

    private Map<String, Object> normalizeMetadata(AIEntityConfig config,
                                                  String vectorSpace,
                                                  String id,
                                                  Map<String, Object> entity,
                                                  Map<String, Object> metadata) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(META_VECTOR_SPACE, vectorSpace);

        if (metadata != null && !metadata.isEmpty()) {
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (!StringUtils.hasText(key) || value == null) {
                    continue;
                }
                out.put(key.trim(), normalizeValue(value));
            }
        }

        if (entity != null && !entity.isEmpty()) {
            Map<String, Object> byLower = toLowerKeyMap(entity);
            List<AIMetadataField> fields = config.getMetadataFields();
            if (fields != null && !fields.isEmpty()) {
                for (AIMetadataField field : fields) {
                    if (field == null || !StringUtils.hasText(field.getName())) {
                        continue;
                    }
                    if (!field.isIncludeInSearch()) {
                        continue;
                    }
                    String name = field.getName().trim();
                    Object raw = byLower.get(name.toLowerCase(Locale.ROOT));
                    if (raw == null) {
                        continue;
                    }
                    out.putIfAbsent(name, normalizeValue(raw));
                }
            }
        }

        out.putIfAbsent("id", id);
        return out;
    }

    private Map<String, Object> toLowerKeyMap(Map<String, Object> input) {
        Map<String, Object> byLower = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key = entry.getKey().toString();
            if (!StringUtils.hasText(key)) {
                continue;
            }
            byLower.put(key.trim().toLowerCase(Locale.ROOT), entry.getValue());
        }
        return byLower;
    }

    private Object normalizeValue(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof String s) {
            String trimmed = s.trim();
            return trimmed.length() > properties.getMaxFieldValueChars()
                ? trimmed.substring(0, properties.getMaxFieldValueChars())
                : trimmed;
        }
        if (raw instanceof Number || raw instanceof Boolean) {
            return raw;
        }
        return toBoundedString(raw, properties.getMaxFieldValueChars());
    }

    private String toBoundedString(Object raw, int maxChars) {
        if (raw == null) {
            return "";
        }
        String out;
        if (raw instanceof String s) {
            out = s;
        } else {
            out = writeJsonSafely(raw);
        }
        if (!StringUtils.hasText(out)) {
            return "";
        }
        String trimmed = out.trim();
        if (maxChars > 0 && trimmed.length() > maxChars) {
            return trimmed.substring(0, maxChars);
        }
        return trimmed;
    }

    private String writeJsonSafely(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    public record NormalizedEntity(String content, Map<String, Object> metadata) {
    }
}
