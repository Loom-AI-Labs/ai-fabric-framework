package com.ai.fabric.realapps.chat.runtime;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RuntimeVectorSearchService {

    private final AICoreService aiCoreService;

    public RuntimeVectorSearchResult search(String vectorSpace, String query, int limit, double threshold) {
        String entityType = requireText(vectorSpace, "vectorSpace is required");
        String searchQuery = requireText(query, "query is required");
        int effectiveLimit = limit <= 0 ? 10 : Math.min(limit, 50);
        double effectiveThreshold = threshold < 0.0d ? 0.0d : threshold;

        AISearchResponse response = aiCoreService.performSearch(AISearchRequest.builder()
            .entityType(entityType)
            .query(searchQuery)
            .limit(effectiveLimit)
            .threshold(effectiveThreshold)
            .build());

        List<Map<String, Object>> results = response == null || response.getResults() == null
            ? List.of()
            : response.getResults().stream()
                .map(RuntimeVectorSearchService::safeResult)
                .toList();

        return new RuntimeVectorSearchResult(
            entityType,
            searchQuery,
            effectiveLimit,
            effectiveThreshold,
            results.size(),
            results
        );
    }

    private static Map<String, Object> safeResult(Map<String, Object> raw) {
        return safeMap(raw, 0, 24);
    }

    private static Map<String, Object> safeMap(Map<?, ?> raw, int depth, int maxKeys) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        if (depth > 3) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            String keyText = key != null ? key.toString() : null;
            if (!StringUtils.hasText(keyText) || value == null || safe.size() >= maxKeys) {
                return;
            }
            if (isSensitiveKey(keyText)) {
                return;
            }
            safe.put(keyText, safeValue(value, depth + 1));
        });
        return Map.copyOf(safe);
    }

    private static Object safeValue(Object value, int depth) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return safeMap(map, depth, 24);
        }
        if (value instanceof List<?> list) {
            if (depth > 3) {
                return List.of();
            }
            return list.stream()
                .limit(24)
                .map(item -> safeValue(item, depth + 1))
                .toList();
        }
        return value.toString();
    }

    private static boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("embedding")
            || normalized.contains("prompt")
            || normalized.contains("secret")
            || normalized.contains("apikey")
            || normalized.contains("api-key")
            || normalized.contains("token");
    }

    private static String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    public record RuntimeVectorSearchResult(
        String vectorSpace,
        String query,
        int limit,
        double threshold,
        int returnedResults,
        List<Map<String, Object>> results
    ) {
    }
}
