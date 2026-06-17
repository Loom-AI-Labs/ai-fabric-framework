package ai.fabric.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

final class RAGMetadataSupport {

    static final String KEY_OPTIMIZED_QUERY_PROVIDED = "optimizedQueryProvided";
    static final String KEY_EMBEDDING_QUERY = "embeddingQuery";
    static final String KEY_OPTIMIZED_QUERY = "optimizedQuery";
    static final String KEY_USER_QUERY = "userQuery";
    static final String KEY_RAW = "raw";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE = new TypeReference<>() {};

    private RAGMetadataSupport() {
    }

    static String resolveEmbeddingQuery(Map<String, Object> metadata, String processedQuery) {
        String explicit = extractEmbeddingQuery(metadata);
        if (StringUtils.hasText(explicit)) {
            return explicit;
        }

        String optimized = extractOptimizedQuery(metadata);
        if (StringUtils.hasText(optimized)) {
            return optimized;
        }
        return processedQuery;
    }

    static String extractEmbeddingQuery(Map<String, Object> metadata) {
        return extractString(metadata, KEY_EMBEDDING_QUERY);
    }

    static String extractUserQuery(Map<String, Object> metadata) {
        return extractString(metadata, KEY_USER_QUERY);
    }

    static String extractOptimizedQuery(Map<String, Object> metadata) {
        return extractString(metadata, KEY_OPTIMIZED_QUERY);
    }

    static Map<String, Object> buildAggregatedMetadata(Map<String, Object> requestMetadata, String embeddingQuery) {
        Map<String, Object> aggregatedMetadata = new HashMap<>();
        if (requestMetadata != null) {
            aggregatedMetadata.putAll(requestMetadata);
        }
        aggregatedMetadata.put(KEY_OPTIMIZED_QUERY_PROVIDED, extractOptimizedQuery(requestMetadata) != null);
        aggregatedMetadata.put(KEY_EMBEDDING_QUERY, embeddingQuery);
        return aggregatedMetadata;
    }

    static Map<String, Object> normalizeMetadata(Object metadata) {
        if (metadata instanceof Map<?, ?> rawMap) {
            return rawMap.entrySet().stream()
                .collect(Collectors.toMap(entry -> String.valueOf(entry.getKey()), Map.Entry::getValue));
        }
        if (metadata instanceof String metadataJson && StringUtils.hasText(metadataJson)) {
            try {
                Map<String, Object> parsed = OBJECT_MAPPER.readValue(metadataJson, MAP_TYPE_REFERENCE);
                return parsed != null ? parsed : Collections.emptyMap();
            } catch (Exception ignored) {
                return Collections.singletonMap(KEY_RAW, metadataJson);
            }
        }
        return Collections.emptyMap();
    }

    static boolean matchesFilters(Map<String, Object> metadata, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }

        Map<String, Object> safeMetadata = metadata != null ? metadata : Collections.emptyMap();
        return filters.entrySet().stream()
            .allMatch(entry -> matchesFilter(safeMetadata, entry.getKey(), entry.getValue()));
    }

    static Double parseDouble(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            String normalized = String.valueOf(value).replaceAll("[^0-9.\\-]", "");
            if (normalized.isEmpty()) {
                return null;
            }
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static double extractScore(Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return 0.0;
        }
        Object score = result.get(RAGDocumentMapper.RESULT_KEY_SCORE);
        if (score instanceof Number number) {
            return number.doubleValue();
        }
        Double parsed = parseDouble(score);
        return parsed != null ? parsed : 0.0;
    }

    private static boolean matchesFilter(Map<String, Object> metadata, String key, Object expected) {
        if (expected == null) {
            return true;
        }

        Object actual = metadata.get(key);
        if (actual == null) {
            return false;
        }

        if (expected instanceof Collection<?> collection) {
            return collection.stream().anyMatch(item -> valuesEqual(actual, item));
        }

        if (expected instanceof Map<?, ?> rangeMap) {
            Double actualValue = parseDouble(actual);
            if (actualValue == null) {
                return false;
            }

            Double min = parseDouble(rangeMap.get("min"));
            Double max = parseDouble(rangeMap.get("max"));

            if (min != null && actualValue < min) {
                return false;
            }
            return max == null || actualValue <= max;
        }

        return valuesEqual(actual, expected);
    }

    private static boolean valuesEqual(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return String.valueOf(actual).equalsIgnoreCase(String.valueOf(expected));
    }

    private static String extractString(Map<String, Object> metadata, String key) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object candidate = metadata.get(key);
        if (candidate instanceof String str && StringUtils.hasText(str)) {
            return str;
        }
        return null;
    }
}
