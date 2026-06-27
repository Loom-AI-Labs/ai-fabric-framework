package ai.fabric.rag.service;

import ai.fabric.core.AISearchService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.RAGRequest;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.rag.source.SearchSource;
import ai.fabric.rag.source.SearchSourceRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
final class RAGSearchExecutor {

    private static final int DEFAULT_RESULT_LIMIT = 10;
    private static final int MAX_MERGED_RESULTS_MULTIPLIER = 20;
    private static final int MAX_MERGED_RESULTS_CAP = 250;
    private static final String SEARCH_PATH_DEFAULT_SEMANTIC = "default_semantic";
    private static final String SEARCH_PATH_VECTOR_DATABASE_HYBRID = "vector_database_hybrid";
    private static final String SEARCH_PATH_VECTOR_DATABASE_CONTEXTUAL = "vector_database_contextual";
    private static final String SEARCH_PATH_SEARCH_SOURCE_REGISTRY = "search_source_registry";
    private static final String HYBRID_MODE_NOT_REQUESTED = "not_requested";
    private static final String HYBRID_MODE_NATIVE = "native";
    private static final String HYBRID_MODE_FALLBACK_VECTOR = "fallback_vector";
    private static final String HYBRID_MODE_SEARCH_SOURCE = "search_source";
    private static final String HYBRID_MODE_NOT_REPORTED_BY_SOURCES = "not_reported_by_sources";

    private final VectorDatabaseService vectorDatabaseService;
    private final AISearchService searchService;
    private final SearchSourceRegistry searchSourceRegistry;

    RAGSearchExecutor(VectorDatabaseService vectorDatabaseService,
                      AISearchService searchService,
                      SearchSourceRegistry searchSourceRegistry) {
        this.vectorDatabaseService = vectorDatabaseService;
        this.searchService = searchService;
        this.searchSourceRegistry = searchSourceRegistry;
    }

    SearchExecutionAggregate performSearch(List<Double> queryVector,
                                            RAGRequest ragRequest,
                                            AISearchRequest baseSearchRequest,
                                            boolean honorSearchMode) {
        if (searchSourceRegistry == null) {
            if (honorSearchMode && Boolean.TRUE.equals(ragRequest.getEnableHybridSearch())) {
                boolean providerSupportsHybrid = vectorDatabaseService.supportsHybridSearch();
                AISearchResponse response = vectorDatabaseService.hybridSearch(
                    queryVector,
                    ragRequest.getQuery(),
                    baseSearchRequest
                );
                return SearchExecutionAggregate.singleResponse(
                    response,
                    SEARCH_PATH_VECTOR_DATABASE_HYBRID,
                    providerSupportsHybrid ? HYBRID_MODE_NATIVE : HYBRID_MODE_FALLBACK_VECTOR,
                    true,
                    providerSupportsHybrid,
                    false,
                    providerSupportsHybrid
                );
            }
            if (honorSearchMode && Boolean.TRUE.equals(ragRequest.getEnableContextualSearch())) {
                AISearchRequest contextualRequest = contextualSearchRequest(baseSearchRequest, ragRequest);
                return SearchExecutionAggregate.singleResponse(
                    vectorDatabaseService.search(queryVector, contextualRequest),
                    SEARCH_PATH_VECTOR_DATABASE_CONTEXTUAL,
                    HYBRID_MODE_NOT_REQUESTED,
                    false,
                    false,
                    true,
                    vectorDatabaseService.supportsHybridSearch()
                );
            }
            return SearchExecutionAggregate.singleResponse(
                searchService.search(queryVector, baseSearchRequest),
                SEARCH_PATH_DEFAULT_SEMANTIC,
                HYBRID_MODE_NOT_REQUESTED,
                false,
                false,
                false,
                vectorDatabaseService.supportsHybridSearch()
            );
        }

        List<SearchSource> sources = searchSourceRegistry.resolveSearchSources(ragRequest);
        if (sources == null || sources.isEmpty()) {
            return SearchExecutionAggregate.empty(baseSearchRequest, ragRequest);
        }

        long startTime = System.currentTimeMillis();
        List<Map<String, Object>> mergedResults = new ArrayList<>();
        List<String> sourceAdapterTypes = new ArrayList<>();
        List<Map<String, Object>> sourceDiagnostics = new ArrayList<>();
        Double maxScore = null;
        int eligibleCount = 0;
        int attemptedCount = 0;
        int succeededCount = 0;
        int failedCount = 0;
        int skippedCount = 0;
        boolean hybridRequested = Boolean.TRUE.equals(ragRequest.getEnableHybridSearch());
        boolean sourceHybridSucceeded = false;

        for (SearchSource source : sources) {
            Map<String, Object> diagnostic = baseSearchSourceDiagnostic(source);
            boolean sourceSupportsHybrid = source.supportsHybridSearch();
            diagnostic.put("supportsHybridSearch", sourceSupportsHybrid);
            diagnostic.put("hybridSearchRequested", hybridRequested);
            if (!source.isEligible(ragRequest)) {
                diagnostic.put("eligible", false);
                diagnostic.put("status", "SKIPPED");
                diagnostic.put("reason", "ineligible");
                sourceDiagnostics.add(immutableDiagnostic(diagnostic));
                skippedCount++;
                continue;
            }

            eligibleCount++;
            attemptedCount++;
            long sourceStartTime = System.currentTimeMillis();
            try {
                AISearchResponse response = source.search(queryVector, ragRequest, baseSearchRequest);
                long sourceProcessingTimeMs = response.getProcessingTimeMs() != null
                    ? response.getProcessingTimeMs()
                    : (System.currentTimeMillis() - sourceStartTime);
                List<Map<String, Object>> sourceResults = response.getResults() != null
                    ? response.getResults()
                    : List.of();

                mergedResults.addAll(sourceResults);
                sourceAdapterTypes.add(source.adapterType());
                if (response.getMaxScore() != null) {
                    maxScore = maxScore == null ? response.getMaxScore() : Math.max(maxScore, response.getMaxScore());
                }
                diagnostic.put("eligible", true);
                diagnostic.put("status", "SUCCEEDED");
                diagnostic.put("processingTimeMs", sourceProcessingTimeMs);
                diagnostic.put("resultsCount", sourceResults.size());
                if (response.getMaxScore() != null) {
                    diagnostic.put("maxScore", response.getMaxScore());
                }
                if (hybridRequested && sourceSupportsHybrid) {
                    sourceHybridSucceeded = true;
                    diagnostic.put("hybridSearchUsed", true);
                } else {
                    diagnostic.put("hybridSearchUsed", false);
                }
                sourceDiagnostics.add(immutableDiagnostic(diagnostic));
                succeededCount++;
            } catch (Exception ex) {
                long sourceProcessingTimeMs = System.currentTimeMillis() - sourceStartTime;
                log.warn(
                    "Search source {} ({}) failed; continuing degraded retrieval: {}",
                    source.sourceId(),
                    source.adapterType(),
                    ex.getMessage()
                );
                log.debug("Search source failure details", ex);
                diagnostic.put("eligible", true);
                diagnostic.put("status", "FAILED");
                diagnostic.put("reason", "search_error");
                diagnostic.put("processingTimeMs", sourceProcessingTimeMs);
                diagnostic.put("errorType", ex.getClass().getSimpleName());
                if (StringUtils.hasText(ex.getMessage())) {
                    diagnostic.put("errorMessage", truncate(ex.getMessage(), 240));
                }
                sourceDiagnostics.add(immutableDiagnostic(diagnostic));
                failedCount++;
            }
        }

        List<Map<String, Object>> rankedResults = rankAndLimitMergedResults(mergedResults, baseSearchRequest.getLimit());
        AISearchResponse response = AISearchResponse.builder()
            .results(rankedResults)
            .totalResults(rankedResults.size())
            .maxScore(maxScore)
            .processingTimeMs(System.currentTimeMillis() - startTime)
            .requestId(ragRequest.getRequestId())
            .query(baseSearchRequest.getQuery())
            .model(String.join(",", new LinkedHashSet<>(sourceAdapterTypes)))
            .build();

        SearchExecutionAggregate aggregate = new SearchExecutionAggregate(
            response,
            List.copyOf(sourceDiagnostics),
            sources.size(),
            eligibleCount,
            attemptedCount,
            succeededCount,
            failedCount,
            skippedCount,
            failedCount > 0,
            SEARCH_PATH_SEARCH_SOURCE_REGISTRY,
            hybridRequested && sourceHybridSucceeded ? HYBRID_MODE_SEARCH_SOURCE :
                (hybridRequested ? HYBRID_MODE_NOT_REPORTED_BY_SOURCES : HYBRID_MODE_NOT_REQUESTED),
            hybridRequested,
            hybridRequested && sourceHybridSucceeded,
            false,
            false
        );
        searchSourceRegistry.recordSearchExecution(aggregate.sourceDiagnostics(), aggregate.degraded());
        return aggregate;
    }

    private AISearchRequest contextualSearchRequest(AISearchRequest request, RAGRequest ragRequest) {
        return AISearchRequest.builder()
            .query(request.getQuery())
            .entityType(request.getEntityType())
            .limit(request.getLimit())
            .threshold(request.getThreshold())
            .filters(request.getFilters())
            .sortBy(request.getSortBy())
            .context(ragRequest.getContext() != null ? ragRequest.getContext().toString() : "")
            .metadata(request.getMetadata())
            .build();
    }

    private List<Map<String, Object>> rankAndLimitMergedResults(List<Map<String, Object>> mergedResults,
                                                                Integer requestedLimit) {
        int effectiveLimit = requestedLimit != null && requestedLimit > 0 ? requestedLimit : DEFAULT_RESULT_LIMIT;
        int maxMergedResults = Math.max(
            effectiveLimit,
            Math.min(MAX_MERGED_RESULTS_CAP, effectiveLimit * MAX_MERGED_RESULTS_MULTIPLIER)
        );
        List<Map<String, Object>> rankedResults = mergedResults.stream()
            .filter(Objects::nonNull)
            .sorted((left, right) -> Double.compare(
                RAGMetadataSupport.extractScore(right),
                RAGMetadataSupport.extractScore(left)
            ))
            .collect(Collectors.toList());
        if (rankedResults.size() > maxMergedResults) {
            rankedResults = new ArrayList<>(rankedResults.subList(0, maxMergedResults));
        }
        LinkedHashMap<String, Map<String, Object>> deduplicated = new LinkedHashMap<>();
        for (Map<String, Object> result : rankedResults) {
            deduplicated.putIfAbsent(resultDedupKey(result), result);
        }
        return deduplicated.values().stream()
            .limit(effectiveLimit)
            .collect(Collectors.toList());
    }

    private Map<String, Object> baseSearchSourceDiagnostic(SearchSource source) {
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        putIfText(diagnostic, "sourceId", source.sourceId());
        putIfText(diagnostic, "sourceType", source.sourceType());
        putIfText(diagnostic, "adapterType", source.adapterType());
        return diagnostic;
    }

    private Map<String, Object> immutableDiagnostic(Map<String, Object> diagnostic) {
        return Map.copyOf(new LinkedHashMap<>(diagnostic));
    }

    private void putIfText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private String resultDedupKey(Map<String, Object> result) {
        Map<String, Object> metadata = RAGMetadataSupport.normalizeMetadata(
            result != null ? result.get(RAGDocumentMapper.RESULT_KEY_METADATA) : null
        );
        String sourceId = metadata.get("knowledgeSourceId") instanceof String text && StringUtils.hasText(text)
            ? text.trim()
            : "";
        String id = result != null && result.get(RAGDocumentMapper.RESULT_KEY_ID) instanceof String text && StringUtils.hasText(text)
            ? text.trim()
            : "";
        String content = result != null && result.get(RAGDocumentMapper.RESULT_KEY_CONTENT) instanceof String text && StringUtils.hasText(text)
            ? truncate(text.trim(), 160)
            : "";
        return sourceId + "|" + id + "|" + content;
    }

    private String truncate(String value, int maxChars) {
        if (!StringUtils.hasText(value) || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }

    record SearchExecutionAggregate(AISearchResponse response,
                                    List<Map<String, Object>> sourceDiagnostics,
                                    int resolvedSourceCount,
                                    int eligibleSourceCount,
                                    int attemptedSourceCount,
                                    int succeededSourceCount,
                                    int failedSourceCount,
                                    int skippedSourceCount,
                                    boolean degraded,
                                    String searchExecutionPath,
                                    String hybridSearchMode,
                                    boolean hybridSearchRequested,
                                    boolean hybridSearchUsed,
                                    boolean contextualSearchUsed,
                                    boolean vectorProviderSupportsHybridSearch) {

        static SearchExecutionAggregate singleResponse(AISearchResponse response) {
            return singleResponse(
                response,
                SEARCH_PATH_DEFAULT_SEMANTIC,
                HYBRID_MODE_NOT_REQUESTED,
                false,
                false,
                false,
                false
            );
        }

        static SearchExecutionAggregate singleResponse(AISearchResponse response,
                                                       String searchExecutionPath,
                                                       String hybridSearchMode,
                                                       boolean hybridSearchRequested,
                                                       boolean hybridSearchUsed,
                                                       boolean contextualSearchUsed,
                                                       boolean vectorProviderSupportsHybridSearch) {
            return new SearchExecutionAggregate(
                response,
                List.of(),
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                searchExecutionPath,
                hybridSearchMode,
                hybridSearchRequested,
                hybridSearchUsed,
                contextualSearchUsed,
                vectorProviderSupportsHybridSearch
            );
        }

        static SearchExecutionAggregate empty(AISearchRequest request, RAGRequest ragRequest) {
            boolean hybridRequested = Boolean.TRUE.equals(ragRequest.getEnableHybridSearch());
            return new SearchExecutionAggregate(
                AISearchResponse.builder()
                    .results(List.of())
                    .totalResults(0)
                    .processingTimeMs(0L)
                    .query(request.getQuery())
                    .model(request.getEntityType())
                    .build(),
                List.of(),
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                SEARCH_PATH_SEARCH_SOURCE_REGISTRY,
                hybridRequested ? HYBRID_MODE_NOT_REPORTED_BY_SOURCES : HYBRID_MODE_NOT_REQUESTED,
                hybridRequested,
                false,
                false,
                false
            );
        }
    }
}
