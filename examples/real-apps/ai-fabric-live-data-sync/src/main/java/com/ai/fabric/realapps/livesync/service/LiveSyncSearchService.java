package com.ai.fabric.realapps.livesync.service;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import com.ai.fabric.realapps.livesync.web.DemoModels.SearchHit;
import com.ai.fabric.realapps.livesync.web.DemoModels.SearchResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class LiveSyncSearchService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AICoreService aiCoreService;
    private final ObjectMapper objectMapper;

    public SearchResponse search(String workspaceId, String query, int requestedLimit) {
        if (!StringUtils.hasText(query)) {
            throw new IllegalArgumentException("query is required");
        }
        int limit = Math.max(1, Math.min(requestedLimit, 9));
        List<SearchHit> hits = new ArrayList<>();
        long processingTimeMs = 0L;
        String model = null;

        for (EntityKind kind : EntityKind.values()) {
            AISearchResponse response = aiCoreService.performSearch(AISearchRequest.builder()
                .query(query.trim())
                .entityType(kind.entityType())
                .limit(Math.min(limit, 4))
                .threshold(0.25d)
                .metadata(Map.of("workspaceId", workspaceId))
                .build());
            if (response == null) {
                continue;
            }
            processingTimeMs += response.getProcessingTimeMs() != null ? response.getProcessingTimeMs() : 0L;
            if (StringUtils.hasText(response.getModel())) {
                model = response.getModel();
            }
            if (response.getResults() != null) {
                response.getResults().stream()
                    .map(this::toHit)
                    .filter(Objects::nonNull)
                    .forEach(hits::add);
            }
        }

        List<SearchHit> limited = hits.stream()
            .sorted(Comparator.comparingDouble(SearchHit::score).reversed())
            .limit(limit)
            .toList();
        Map<String, Integer> counts = new LinkedHashMap<>();
        limited.forEach(hit -> counts.merge(hit.entityType(), 1, Integer::sum));
        return new SearchResponse(
            query.trim(),
            limited,
            Map.copyOf(counts),
            processingTimeMs,
            model
        );
    }

    private SearchHit toHit(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        Map<String, Object> metadata = metadata(row.get("metadata"));
        String id = string(row.get("id"));
        String entityType = string(row.get("entityType"));
        String content = string(row.get("content"));
        if (!StringUtils.hasText(id) || !StringUtils.hasText(entityType) || !StringUtils.hasText(content)) {
            return null;
        }
        return new SearchHit(
            id,
            firstText(string(metadata.get("title")), string(metadata.get("recordKey")), id),
            entityType,
            firstText(string(metadata.get("recordKey")), id),
            content,
            doubleValue(row.get("score")),
            Map.copyOf(metadata)
        );
    }

    private Map<String, Object> metadata(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            map.forEach((key, item) -> converted.put(String.valueOf(key), item));
            return converted;
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return objectMapper.readValue(text, MAP_TYPE);
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        return Map.of();
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(value.toString());
            } catch (NumberFormatException ignored) {
                return 0.0d;
            }
        }
        return 0.0d;
    }

    private String string(Object value) {
        return value != null ? value.toString() : null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "Untitled evidence";
    }
}
