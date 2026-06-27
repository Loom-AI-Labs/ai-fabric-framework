package ai.fabric.util;

import ai.fabric.dto.AIEntityConfig;
import ai.fabric.dto.AIMetadataField;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
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
                appendJsonEntry(json, key, metadata.get(key));
                first = false;
            }
        }

        for (Map.Entry<String, Object> entry : ensureLinked(metadata).entrySet()) {
            String key = entry.getKey();
            if (config != null && config.getMetadataFields() != null
                && config.getMetadataFields().stream().anyMatch(field -> Objects.equals(field.getName(), key))) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            appendJsonEntry(json, key, entry.getValue());
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
            appendJsonEntry(json, entry.getKey(), entry.getValue());
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
        List<Map.Entry<String, Object>> entries = new ArrayList<>(metadata.entrySet());
        entries.sort((left, right) -> String.valueOf(left.getKey()).compareTo(String.valueOf(right.getKey())));
        LinkedHashMap<String, Object> ordered = new LinkedHashMap<>();
        entries.forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
    }

    private static void appendJsonEntry(StringBuilder json, String key, Object value) {
        json.append('"').append(escapeKey(key)).append('"').append(':')
            .append('"').append(escapeValue(value)).append('"');
    }

    private static String escapeKey(String key) {
        return escapeJsonString(String.valueOf(key));
    }

    private static String escapeValue(Object value) {
        if (value == null) {
            return "";
        }
        return escapeJsonString(value.toString());
    }

    private static String escapeJsonString(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        appendUnicodeEscape(escaped, ch);
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static void appendUnicodeEscape(StringBuilder target, char ch) {
        target.append("\\u");
        String hex = Integer.toHexString(ch);
        for (int i = hex.length(); i < 4; i++) {
            target.append('0');
        }
        target.append(hex);
    }
}
