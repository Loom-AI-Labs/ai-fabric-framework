package com.ai.infrastructure.util;

import com.ai.infrastructure.dto.AIEntityConfig;
import com.ai.infrastructure.dto.AIMetadataField;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Utility for serializing metadata maps into JSON strings with deterministic ordering.
 */
public final class MetadataJsonSerializer {

    private MetadataJsonSerializer() {
    }

    public static String serialize(Map<String, Object> metadata, AIEntityConfig config) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }

        StringBuilder json = new StringBuilder("{");
        boolean first = true;

        if (config != null && config.getMetadataFields() != null) {
            for (AIMetadataField field : config.getMetadataFields()) {
                String key = field.getName();
                if (!metadata.containsKey(key)) {
                    continue;
                }
                if (!first) {
                    json.append(',');
                }
                json.append('"').append(key).append('"').append(':')
                    .append('"').append(escape(metadata.get(key))).append('"');
                first = false;
            }
        }

        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            String key = entry.getKey();
            if (config != null && config.getMetadataFields() != null
                && config.getMetadataFields().stream().anyMatch(field -> Objects.equals(field.getName(), key))) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            json.append('"').append(key).append('"').append(':')
                .append('"').append(escape(entry.getValue())).append('"');
            first = false;
        }

        json.append('}');
        return json.toString();
    }

    public static String serialize(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }

        StringBuilder json = new StringBuilder("{");
        Iterator<Map.Entry<String, Object>> iterator = ensureLinked(metadata).entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Object> entry = iterator.next();
            json.append('"').append(entry.getKey()).append('"').append(':')
                .append('"').append(escape(entry.getValue())).append('"');
            if (iterator.hasNext()) {
                json.append(',');
            }
        }
        json.append('}');
        return json.toString();
    }

    private static Map<String, Object> ensureLinked(Map<String, Object> metadata) {
        if (metadata instanceof LinkedHashMap) {
            return metadata;
        }
        LinkedHashMap<String, Object> ordered = new LinkedHashMap<>();
        metadata.forEach(ordered::put);
        return ordered;
    }

    private static String escape(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().replace("\"", "\\\"");
    }
}
