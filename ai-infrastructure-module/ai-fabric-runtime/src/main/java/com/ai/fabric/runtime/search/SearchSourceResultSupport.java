package com.ai.fabric.runtime.search;

import com.ai.infrastructure.dto.AISearchRequest;
import com.ai.infrastructure.dto.AISearchResponse;
import com.ai.infrastructure.rag.source.ResolvedKnowledgeSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class SearchSourceResultSupport {

    private static final String DEPLOYMENT_PRIVATE_VECTOR_ADAPTER = "deployment-private-vector";

    static final String METADATA_KEY_KNOWLEDGE_SOURCE_ID = "knowledgeSourceId";
    static final String METADATA_KEY_KNOWLEDGE_SOURCE_TYPE = "knowledgeSourceType";
    static final String METADATA_KEY_KNOWLEDGE_SOURCE_ADAPTER_TYPE = "knowledgeSourceAdapterType";
    static final String METADATA_KEY_KNOWLEDGE_SOURCE_ATTRIBUTION_LABEL = "knowledgeSourceAttributionLabel";
    static final String METADATA_KEY_KNOWLEDGE_SOURCE_HANDLE_REF = "knowledgeSourceHandleRef";

    private SearchSourceResultSupport() {
    }

    static AISearchRequest scopedRequest(AISearchRequest baseRequest,
                                         ResolvedKnowledgeSource source,
                                         int limitMultiplier) {
        int baseLimit = baseRequest.getLimit() != null ? baseRequest.getLimit() : 10;
        int effectiveLimit = Math.max(baseLimit, Math.min(100, baseLimit * Math.max(limitMultiplier, 1)));
        Map<String, Object> mergedMetadata = new LinkedHashMap<>();
        if (baseRequest.getMetadata() != null) {
            mergedMetadata.putAll(baseRequest.getMetadata());
        }
        if (source.getFilters() != null) {
            mergedMetadata.putAll(source.getFilters());
        }
        return AISearchRequest.builder()
            .query(baseRequest.getQuery())
            .entityType(source.getEntityType() != null ? source.getEntityType() : baseRequest.getEntityType())
            .limit(effectiveLimit)
            .threshold(baseRequest.getThreshold())
            .filters(baseRequest.getFilters())
            .sortBy(baseRequest.getSortBy())
            .context(baseRequest.getContext())
            .metadata(mergedMetadata.isEmpty() ? null : Map.copyOf(mergedMetadata))
            .build();
    }

    static AISearchResponse filterAndDecorate(AISearchResponse response,
                                              ResolvedKnowledgeSource source,
                                              Map<String, Object> requiredMetadata) {
        List<Map<String, Object>> filteredResults = new ArrayList<>();
        if (response.getResults() != null) {
            for (Map<String, Object> result : response.getResults()) {
                Map<String, Object> metadata = normalizeMetadata(result.get("metadata"));
                if (!matchesFilters(metadata, requiredMetadata)) {
                    continue;
                }
                if (DEPLOYMENT_PRIVATE_VECTOR_ADAPTER.equalsIgnoreCase(source.getAdapterType())
                    && metadata.containsKey(METADATA_KEY_KNOWLEDGE_SOURCE_HANDLE_REF)) {
                    continue;
                }
                Map<String, Object> decorated = new LinkedHashMap<>(result);
                Map<String, Object> decoratedMetadata = new LinkedHashMap<>(metadata);
                decoratedMetadata.put(METADATA_KEY_KNOWLEDGE_SOURCE_ID, source.getId());
                decoratedMetadata.put(METADATA_KEY_KNOWLEDGE_SOURCE_TYPE, source.getType());
                decoratedMetadata.put(METADATA_KEY_KNOWLEDGE_SOURCE_ADAPTER_TYPE, source.getAdapterType());
                decoratedMetadata.put(METADATA_KEY_KNOWLEDGE_SOURCE_ATTRIBUTION_LABEL, source.getAttributionLabel());
                if (source.getHandleRef() != null) {
                    decoratedMetadata.put(METADATA_KEY_KNOWLEDGE_SOURCE_HANDLE_REF, source.getHandleRef());
                }
                decorated.put("metadata", decoratedMetadata);
                filteredResults.add(decorated);
            }
        }

        Double maxScore = filteredResults.stream()
            .map(entry -> entry.get("score"))
            .filter(Number.class::isInstance)
            .map(Number.class::cast)
            .map(Number::doubleValue)
            .max(Double::compareTo)
            .orElse(response.getMaxScore());

        return AISearchResponse.builder()
            .results(List.copyOf(filteredResults))
            .totalResults(filteredResults.size())
            .maxScore(maxScore)
            .processingTimeMs(response.getProcessingTimeMs())
            .requestId(response.getRequestId())
            .query(response.getQuery())
            .model(response.getModel())
            .build();
    }

    private static Map<String, Object> normalizeMetadata(Object metadata) {
        if (!(metadata instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> {
            if (key instanceof String textKey) {
                normalized.put(textKey, value);
            }
        });
        return normalized;
    }

    private static boolean matchesFilters(Map<String, Object> metadata, Map<String, Object> requiredMetadata) {
        if (requiredMetadata == null || requiredMetadata.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Object> entry : requiredMetadata.entrySet()) {
            Object actual = metadata.get(entry.getKey());
            if (!Objects.equals(actual, entry.getValue())) {
                return false;
            }
        }
        return true;
    }
}
