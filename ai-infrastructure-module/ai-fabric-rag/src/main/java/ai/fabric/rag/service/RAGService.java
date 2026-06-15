package ai.fabric.rag.service;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.core.AISearchService;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.exception.AIServiceException;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.rag.source.SearchSource;
import ai.fabric.rag.source.SearchSourceRegistry;
import ai.fabric.spi.RAGProvider;
import ai.fabric.vector.VectorDatabase;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

/**
 * RAG (Retrieval-Augmented Generation) Service implementation.
 * 
 * <p><strong>Retrieval-only:</strong> This service performs indexing + retrieval and builds
 * context strings for downstream generation. It does <em>not</em> perform LLM generation.</p>
 * 
 * <p>Queries passed to this service are assumed to be pre-processed by the
 * orchestrator (PII redacted, sanitized, normalized). The service performs no
 * additional PII detection or sanitization.</p>
 * 
 * <p>This is the default implementation of {@link RAGProvider} SPI, providing:</p>
 * <ul>
 *   <li>Content indexing with embeddings</li>
 *   <li>Semantic search with vector similarity</li>
 *   <li>Hybrid and contextual search modes</li>
 *   <li>Context building for downstream generation</li>
 * </ul>
 * 
 * <p><strong>Thread Safety:</strong> This service is thread-safe and can be used
 * concurrently by multiple threads.</p>
 * 
 * @author AI Infrastructure Team
 * @version 2.0.0
 * @see RAGProvider
 * @since 1.0
 */
@Slf4j
@RequiredArgsConstructor
public class RAGService implements RAGProvider {
    
    // =========================================================================
    // Constants
    // =========================================================================
    
    private static final String PROVIDER_NAME = "default-rag-service";
    
    // Metadata keys
    private static final String METADATA_KEY_OPTIMIZED_QUERY_PROVIDED = "optimizedQueryProvided";
    private static final String METADATA_KEY_EMBEDDING_QUERY = "embeddingQuery";
    private static final String METADATA_KEY_OPTIMIZED_QUERY = "optimizedQuery";
    private static final String METADATA_KEY_USER_QUERY = "userQuery";
    private static final String METADATA_KEY_RAG_TOTAL_PROCESSING_TIME_MS = "ragTotalProcessingTimeMs";
    private static final String METADATA_KEY_EMBEDDING_PROCESSING_TIME_MS = "embeddingProcessingTimeMs";
    private static final String METADATA_KEY_EMBEDDING_PROVIDER_PROCESSING_TIME_MS = "embeddingProviderProcessingTimeMs";
    private static final String METADATA_KEY_EMBEDDING_CACHE_HIT = "embeddingCacheHit";
    private static final String METADATA_KEY_EMBEDDING_PROVIDER_NAME = "embeddingProviderName";
    private static final String METADATA_KEY_EMBEDDING_MODEL = "embeddingModel";
    private static final String METADATA_KEY_SEARCH_PROCESSING_TIME_MS = "searchProcessingTimeMs";
    private static final String METADATA_KEY_SEARCH_REQUEST_ID = "searchRequestId";
    private static final String METADATA_KEY_SEARCH_MAX_SCORE = "searchMaxScore";
    private static final String METADATA_KEY_SEARCH_SOURCE_COUNT = "searchSourceCount";
    private static final String METADATA_KEY_SEARCH_SOURCE_IDS = "searchSourceIds";
    private static final String METADATA_KEY_SEARCH_SOURCE_TYPES = "searchSourceTypes";
    private static final String METADATA_KEY_SEARCH_SOURCE_ADAPTER_TYPES = "searchSourceAdapterTypes";
    private static final String METADATA_KEY_SEARCH_SOURCE_DIAGNOSTICS = "searchSourceDiagnostics";
    private static final String METADATA_KEY_SEARCH_SOURCE_ELIGIBLE_COUNT = "searchSourceEligibleCount";
    private static final String METADATA_KEY_SEARCH_SOURCE_ATTEMPTED_COUNT = "searchSourceAttemptedCount";
    private static final String METADATA_KEY_SEARCH_SOURCE_SUCCEEDED_COUNT = "searchSourceSucceededCount";
    private static final String METADATA_KEY_SEARCH_SOURCE_FAILED_COUNT = "searchSourceFailedCount";
    private static final String METADATA_KEY_SEARCH_SOURCE_SKIPPED_COUNT = "searchSourceSkippedCount";
    private static final String METADATA_KEY_SEARCH_SOURCES_DEGRADED = "searchSourcesDegraded";
    
    // Result keys
    private static final String RESULT_KEY_CONTENT = "content";
    private static final String RESULT_KEY_SCORE = "score";
    private static final String RESULT_KEY_SIMILARITY = "similarity";
    private static final String RESULT_KEY_ID = "id";
    private static final String RESULT_KEY_TITLE = "title";
    private static final String RESULT_KEY_VECTOR_SPACE = "vectorSpace";
    private static final String RESULT_KEY_ENTITY_TYPE = "entityType";
    private static final String RESULT_KEY_METADATA = "metadata";
    private static final String RESULT_KEY_RAW = "raw";
    
    // Default values
    private static final double DEFAULT_SEARCH_THRESHOLD = 0.7;
    private static final int DEFAULT_RESULT_LIMIT = 10;
    private static final int MAX_MERGED_RESULTS_MULTIPLIER = 20;
    private static final int MAX_MERGED_RESULTS_CAP = 250;
    
    // Messages
    private static final String NO_CONTEXT_MESSAGE = "No relevant context found.";
    private static final String CONTEXT_HEADER = "Relevant Context:\n\n";
    
    // =========================================================================
    // Dependencies
    // =========================================================================
    
    private final AIProviderConfig config;
    private final AIEmbeddingService embeddingService;
    private final VectorDatabaseService vectorDatabaseService;
    private final VectorDatabase vectorDatabase;
    private final AISearchService searchService;
    private final SearchSourceRegistry searchSourceRegistry;
    
    // =========================================================================
    // JSON Processing
    // =========================================================================
    
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE = new TypeReference<>() {};
    
    // =========================================================================
    // RAGProvider Implementation
    // =========================================================================
    
    /**
     * {@inheritDoc}
     */
    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public void indexContent(String entityType, String entityId, String content, Map<String, Object> metadata) {
        try {
            log.debug("Indexing content for entity {} of type {}", entityId, entityType);
            
            AIEmbeddingRequest embeddingRequest = AIEmbeddingRequest.builder()
                .text(content)
                .entityType(entityType)
                .entityId(entityId)
                .metadata(metadata != null ? metadata.toString() : null)
                .build();
            
            var embeddingResponse = embeddingService.generateEmbedding(embeddingRequest);
            
            vectorDatabaseService.storeVector(entityType, entityId, content, 
                embeddingResponse.getEmbedding(), metadata);
            
            log.debug("Successfully indexed content for entity {} of type {}", entityId, entityType);
            
        } catch (Exception e) {
            log.error("Error indexing content for entity {} of type {}", entityId, entityType, e);
            throw new AIServiceException("Failed to index content", e);
        }
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public void removeContent(String entityType, String entityId) {
        try {
            log.debug("Removing content for entity {} of type {}", entityId, entityType);
            
            vectorDatabaseService.removeVector(entityType, entityId);
            
            log.debug("Successfully removed content for entity {} of type {}", entityId, entityType);
            
        } catch (Exception e) {
            log.error("Error removing content for entity {} of type {}", entityId, entityType, e);
            throw new AIServiceException("Failed to remove content", e);
        }
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalIndexed", vectorDatabaseService.getStatistics());
        stats.put("vectorDatabase", vectorDatabase.getStatistics());
        return stats;
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public RAGResponse performRag(RAGRequest request) {
        try {
            long startTime = System.currentTimeMillis();
            String processedQuery = request.getQuery();
            String embeddingQuery = resolveEmbeddingQuery(request, processedQuery);
            
            log.debug("Performing RAG operation (entityType={}, requestId={})",
                request.getEntityType(),
                request.getRequestId());

            AIEmbeddingRequest embeddingRequest = AIEmbeddingRequest.builder()
                .text(embeddingQuery)
                .build();
            
            AIEmbeddingService.EmbeddingExecution embeddingExecution = embeddingService.executeEmbedding(embeddingRequest);
            AIEmbeddingResponse embeddingResponse = embeddingExecution.response();
            long embeddingDurationMs = embeddingExecution.serviceProcessingTimeMs() != null
                ? embeddingExecution.serviceProcessingTimeMs()
                : 0L;
            List<Double> queryVector = embeddingResponse.getEmbedding();
            
            String contextString = request.getContext() != null ? request.getContext().toString() : null;
            String filtersString = request.getFilters() != null ? request.getFilters().toString() : null;
            
            AISearchRequest searchRequest = AISearchRequest.builder()
                .query(embeddingQuery)
                .entityType(request.getEntityType())
                .limit(request.getLimit())
                .threshold(request.getThreshold())
                .context(contextString)
                .filters(filtersString)
                .metadata(request.getMetadata())
                .build();
            
            SearchExecutionAggregate searchExecution = performSearch(queryVector, request, searchRequest, false);
            AISearchResponse searchResponse = searchExecution.response();
            long totalProcessingTimeMs = System.currentTimeMillis() - startTime;
            
            Map<String, Object> filters = request.getFilters();

	            List<RAGResponse.RAGDocument> documents = searchResponse.getResults().stream()
	                .map(result -> {
	                    Map<String, Object> normalizedMetadata = new LinkedHashMap<>(normalizeMetadata(result.get(RESULT_KEY_METADATA)));
	                    if (!matchesFilters(normalizedMetadata, filters)) {
	                        return null;
	                    }

	                    String vectorSpace = resolveVectorSpace(result);
	                    if (StringUtils.hasText(vectorSpace)) {
	                        normalizedMetadata.put(RESULT_KEY_VECTOR_SPACE, vectorSpace);
	                    }

                    return RAGResponse.RAGDocument.builder()
                        .id((String) result.get(RESULT_KEY_ID))
                        .content((String) result.get(RESULT_KEY_CONTENT))
                        .title((String) result.get(RESULT_KEY_TITLE))
                        .type(vectorSpace)
                        .score((Double) result.get(RESULT_KEY_SCORE))
                        .similarity((Double) result.get(RESULT_KEY_SIMILARITY))
                        .metadata(normalizedMetadata)
                        .source(resolveAttributionLabel(normalizedMetadata))
                        .build();
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

            String context = buildContextFromDocuments(documents);

            Map<String, Object> aggregatedMetadata = buildAggregatedMetadata(
                request.getMetadata(), embeddingQuery);
            aggregatedMetadata.put(METADATA_KEY_RAG_TOTAL_PROCESSING_TIME_MS, totalProcessingTimeMs);
            aggregatedMetadata.put(METADATA_KEY_EMBEDDING_PROCESSING_TIME_MS, embeddingDurationMs);
            aggregatedMetadata.put(METADATA_KEY_EMBEDDING_PROVIDER_PROCESSING_TIME_MS, embeddingExecution.providerProcessingTimeMs());
            aggregatedMetadata.put(METADATA_KEY_EMBEDDING_CACHE_HIT, embeddingExecution.cacheHit());
            aggregatedMetadata.put(METADATA_KEY_EMBEDDING_PROVIDER_NAME, embeddingExecution.providerName());
            aggregatedMetadata.put(METADATA_KEY_EMBEDDING_MODEL, embeddingExecution.effectiveModel());
            aggregatedMetadata.put(METADATA_KEY_SEARCH_PROCESSING_TIME_MS, searchResponse.getProcessingTimeMs());
            if (StringUtils.hasText(searchResponse.getRequestId())) {
                aggregatedMetadata.put(METADATA_KEY_SEARCH_REQUEST_ID, searchResponse.getRequestId());
            }
            if (searchResponse.getMaxScore() != null) {
                aggregatedMetadata.put(METADATA_KEY_SEARCH_MAX_SCORE, searchResponse.getMaxScore());
            }
            appendSearchSourceMetadata(aggregatedMetadata, searchResponse);
            appendSearchExecutionMetadata(aggregatedMetadata, searchExecution);

            String originalUserQuery = extractUserQuery(request.getMetadata());
            
            return RAGResponse.builder()
                .context(context)
                .documents(documents)
                .totalDocuments(searchResponse.getTotalResults())
                .usedDocuments(documents.size())
                .relevanceScores(documents.stream()
                    .map(RAGResponse.RAGDocument::getScore)
                    .collect(Collectors.toList()))
                .success(true)
                .totalResults(searchResponse.getTotalResults())
                .returnedResults(documents.size())
                .maxScore(documents.stream()
                    .mapToDouble(RAGResponse.RAGDocument::getScore)
                    .max().orElse(0.0))
                .averageScore(documents.stream()
                    .mapToDouble(RAGResponse.RAGDocument::getScore)
                    .average().orElse(0.0))
                .processingTimeMs(totalProcessingTimeMs)
                .requestId(request.getRequestId())
                .originalQuery(StringUtils.hasText(originalUserQuery) ? originalUserQuery : processedQuery)
                .entityType(request.getEntityType())
                .model(config.resolveEmbeddingDefaults().model())
                .timestamp(java.time.LocalDateTime.now())
                .metadata(Collections.unmodifiableMap(aggregatedMetadata))
                .build();
                
        } catch (Exception e) {
            log.error("Error performing RAG operation", e);
            return RAGResponse.builder()
                .success(false)
                .errorMessage("Failed to perform RAG operation: " + e.getMessage())
                .build();
        }
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public RAGResponse performRAGQuery(RAGRequest request) {
        try {
            String processedQuery = request.getQuery();
            String embeddingQuery = resolveEmbeddingQuery(request, processedQuery);
            
            log.debug("Performing RAG query (entityType={}, requestId={})",
                request.getEntityType(),
                request.getRequestId());
            
            long startTime = System.currentTimeMillis();
            
            AIEmbeddingRequest embeddingRequest = AIEmbeddingRequest.builder()
                .text(embeddingQuery)
                .model(config.resolveEmbeddingDefaults().model())
                .build();
            
            AIEmbeddingService.EmbeddingExecution embeddingExecution = embeddingService.executeEmbedding(embeddingRequest);
            var embeddingResponse = embeddingExecution.response();
            long embeddingDurationMs = embeddingExecution.serviceProcessingTimeMs() != null
                ? embeddingExecution.serviceProcessingTimeMs()
                : 0L;
            List<Double> queryVector = embeddingResponse.getEmbedding();
            
            AISearchRequest searchRequest = AISearchRequest.builder()
                .query(embeddingQuery)
                .entityType(request.getEntityType())
                .limit(request.getLimit())
                .threshold(request.getThreshold())
                .build();
            
            SearchExecutionAggregate searchExecution = performSearch(queryVector, request, searchRequest, true);
            AISearchResponse searchResponse = searchExecution.response();
            
            String context = buildContext(searchResponse);
            
            long processingTime = System.currentTimeMillis() - startTime;

            Map<String, Object> aggregatedMetadata = buildAggregatedMetadata(
                request.getMetadata(), embeddingQuery);
            aggregatedMetadata.put(METADATA_KEY_RAG_TOTAL_PROCESSING_TIME_MS, processingTime);
            aggregatedMetadata.put(METADATA_KEY_EMBEDDING_PROCESSING_TIME_MS, embeddingDurationMs);
            aggregatedMetadata.put(METADATA_KEY_EMBEDDING_PROVIDER_PROCESSING_TIME_MS, embeddingExecution.providerProcessingTimeMs());
            aggregatedMetadata.put(METADATA_KEY_EMBEDDING_CACHE_HIT, embeddingExecution.cacheHit());
            aggregatedMetadata.put(METADATA_KEY_EMBEDDING_PROVIDER_NAME, embeddingExecution.providerName());
            aggregatedMetadata.put(METADATA_KEY_EMBEDDING_MODEL, embeddingExecution.effectiveModel());
            aggregatedMetadata.put(METADATA_KEY_SEARCH_PROCESSING_TIME_MS, searchResponse.getProcessingTimeMs());
            if (StringUtils.hasText(searchResponse.getRequestId())) {
                aggregatedMetadata.put(METADATA_KEY_SEARCH_REQUEST_ID, searchResponse.getRequestId());
            }
            if (searchResponse.getMaxScore() != null) {
                aggregatedMetadata.put(METADATA_KEY_SEARCH_MAX_SCORE, searchResponse.getMaxScore());
            }
            appendSearchSourceMetadata(aggregatedMetadata, searchResponse);
            appendSearchExecutionMetadata(aggregatedMetadata, searchExecution);

            String originalUserQuery = extractUserQuery(request.getMetadata());
            
            return RAGResponse.builder()
                .context(context)
                .documents(convertToRAGDocuments(searchResponse.getResults()))
                .totalDocuments(searchResponse.getTotalResults())
                .usedDocuments(Math.min(searchResponse.getTotalResults(), request.getLimit()))
                .confidenceScore(calculateConfidence(searchResponse))
                .relevanceScores(searchResponse.getResults().stream()
                    .map(doc -> (Double) doc.get(RESULT_KEY_SIMILARITY))
                    .collect(Collectors.toList()))
                .processingTimeMs(processingTime)
                .requestId(request.getRequestId())
                .model(config.resolveEmbeddingDefaults().model())
                .success(true)
                .hybridSearchUsed(Boolean.TRUE.equals(request.getEnableHybridSearch()))
                .contextualSearchUsed(Boolean.TRUE.equals(request.getEnableContextualSearch()))
                .originalQuery(StringUtils.hasText(originalUserQuery) ? originalUserQuery : processedQuery)
                .entityType(request.getEntityType())
                .searchedCategories(request.getCategories())
                .metadata(Collections.unmodifiableMap(aggregatedMetadata))
                .build();
                
        } catch (Exception e) {
            log.error("Error performing RAG query", e);
            return RAGResponse.builder()
                .context("")
                .documents(Collections.emptyList())
                .totalDocuments(0)
                .usedDocuments(0)
                .confidenceScore(0.0)
                .relevanceScores(Collections.emptyList())
                .processingTimeMs(0L)
                .requestId(request.getRequestId())
                .model(config.resolveEmbeddingDefaults().model())
                .success(false)
                .errorMessage(e.getMessage())
                .build();
        }
    }

    private String buildContext(AISearchResponse searchResponse) {
        if (searchResponse.getResults().isEmpty()) {
            return NO_CONTEXT_MESSAGE;
        }
        
        StringBuilder context = new StringBuilder();
        context.append(CONTEXT_HEADER);
        
        for (int i = 0; i < searchResponse.getResults().size(); i++) {
            Map<String, Object> result = searchResponse.getResults().get(i);
            context.append(String.format("%d. %s (Score: %.3f)\n", 
                i + 1, result.get(RESULT_KEY_CONTENT), result.get(RESULT_KEY_SCORE)));
        }
        
        return context.toString();
    }
    
    // =========================================================================
    // Private Helper Methods
    // =========================================================================
    
    private AISearchResponse performHybridSearch(List<Double> queryVector, String queryText, 
            AISearchRequest request) {
        return vectorDatabaseService.hybridSearch(queryVector, queryText, request);
    }
    
    private AISearchResponse performContextualSearch(List<Double> queryVector, String context, 
            AISearchRequest request) {
        AISearchRequest contextualRequest = AISearchRequest.builder()
            .query(request.getQuery())
            .entityType(request.getEntityType())
            .limit(request.getLimit())
            .threshold(request.getThreshold())
            .filters(request.getFilters())
            .sortBy(request.getSortBy())
            .context(context)
            .metadata(request.getMetadata())
            .build();

        return vectorDatabaseService.search(queryVector, contextualRequest);
    }

    private SearchExecutionAggregate performSearch(List<Double> queryVector,
                                                   RAGRequest ragRequest,
                                                   AISearchRequest baseSearchRequest,
                                                   boolean honorSearchMode) {
        if (searchSourceRegistry == null) {
            if (honorSearchMode && Boolean.TRUE.equals(ragRequest.getEnableHybridSearch())) {
                AISearchResponse response = performHybridSearch(queryVector, ragRequest.getQuery(), baseSearchRequest);
                return SearchExecutionAggregate.singleResponse(response);
            }
            if (honorSearchMode && Boolean.TRUE.equals(ragRequest.getEnableContextualSearch())) {
                AISearchResponse response = performContextualSearch(
                    queryVector,
                    ragRequest.getContext() != null ? ragRequest.getContext().toString() : "",
                    baseSearchRequest
                );
                return SearchExecutionAggregate.singleResponse(response);
            }
            return SearchExecutionAggregate.singleResponse(searchService.search(queryVector, baseSearchRequest));
        }

        List<SearchSource> sources = searchSourceRegistry.resolveSearchSources(ragRequest);
        if (sources.isEmpty()) {
            return SearchExecutionAggregate.empty(baseSearchRequest);
        }

        long startTime = System.currentTimeMillis();
        List<Map<String, Object>> mergedResults = new ArrayList<>();
        List<String> sourceIds = new ArrayList<>();
        List<String> sourceTypes = new ArrayList<>();
        List<String> sourceAdapterTypes = new ArrayList<>();
        List<Map<String, Object>> sourceDiagnostics = new ArrayList<>();
        Double maxScore = null;
        int eligibleCount = 0;
        int attemptedCount = 0;
        int succeededCount = 0;
        int failedCount = 0;
        int skippedCount = 0;

        for (SearchSource source : sources) {
            Map<String, Object> diagnostic = baseSearchSourceDiagnostic(source);
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
                List<Map<String, Object>> sourceResults = response.getResults() != null ? response.getResults() : List.of();
                mergedResults.addAll(sourceResults);
                sourceIds.add(source.sourceId());
                sourceTypes.add(source.sourceType());
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
                sourceDiagnostics.add(immutableDiagnostic(diagnostic));
                succeededCount++;
            } catch (Exception ex) {
                long sourceProcessingTimeMs = System.currentTimeMillis() - sourceStartTime;
                log.warn(
                    "Search source {} ({}) failed; continuing degraded retrieval",
                    source.sourceId(),
                    source.adapterType(),
                    ex
                );
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
            failedCount > 0
        );
        searchSourceRegistry.recordSearchExecution(aggregate.sourceDiagnostics(), aggregate.degraded());
        return aggregate;
    }

    private List<Map<String, Object>> rankAndLimitMergedResults(List<Map<String, Object>> mergedResults, Integer requestedLimit) {
        int effectiveLimit = requestedLimit != null ? requestedLimit : DEFAULT_RESULT_LIMIT;
        int maxMergedResults = Math.max(
            effectiveLimit,
            Math.min(MAX_MERGED_RESULTS_CAP, effectiveLimit * MAX_MERGED_RESULTS_MULTIPLIER)
        );
        List<Map<String, Object>> rankedResults = mergedResults.stream()
            .filter(Objects::nonNull)
            .sorted((left, right) -> Double.compare(extractScore(right), extractScore(left)))
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
    
    private double calculateConfidence(AISearchResponse searchResponse) {
        if (searchResponse.getResults().isEmpty()) {
            return 0.0;
        }
        
        return searchResponse.getResults().stream()
            .mapToDouble(doc -> {
                Object similarity = doc.get(RESULT_KEY_SIMILARITY);
                return similarity instanceof Number ? ((Number) similarity).doubleValue() : 0.0;
            })
            .average()
            .orElse(0.0);
    }
    
    private List<RAGResponse.RAGDocument> convertToRAGDocuments(List<Map<String, Object>> results) {
        return results.stream()
            .map(this::convertToRAGDocument)
            .collect(Collectors.toList());
    }
    
	    private RAGResponse.RAGDocument convertToRAGDocument(Map<String, Object> result) {
	        Map<String, Object> metadata = new LinkedHashMap<>(normalizeMetadata(result.get(RESULT_KEY_METADATA)));
	        String vectorSpace = resolveVectorSpace(result);
	        if (StringUtils.hasText(vectorSpace)) {
	            metadata.put(RESULT_KEY_VECTOR_SPACE, vectorSpace);
	        }

        Object scoreValue = result.getOrDefault(RESULT_KEY_SCORE, 0.0);
        Object similarityValue = result.getOrDefault(RESULT_KEY_SIMILARITY, 0.0);

        double score = scoreValue instanceof Number ? ((Number) scoreValue).doubleValue() 
            : parseDouble(scoreValue) != null ? parseDouble(scoreValue) : 0.0;
        double similarity = similarityValue instanceof Number ? ((Number) similarityValue).doubleValue() 
            : parseDouble(similarityValue) != null ? parseDouble(similarityValue) : 0.0;

        return RAGResponse.RAGDocument.builder()
            .id((String) result.get(RESULT_KEY_ID))
            .content((String) result.get(RESULT_KEY_CONTENT))
            .title((String) result.get(RESULT_KEY_TITLE))
            .type(vectorSpace)
            .score(score)
            .similarity(similarity)
            .metadata(metadata)
            .source(resolveAttributionLabel(metadata))
            .build();
    }

    private String resolveVectorSpace(Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return null;
        }

        Object direct = result.get(RESULT_KEY_VECTOR_SPACE);
        if (direct instanceof String vs && StringUtils.hasText(vs)) {
            return vs.trim();
        }

        Object entityType = result.get(RESULT_KEY_ENTITY_TYPE);
        if (entityType instanceof String et && StringUtils.hasText(et)) {
            return et.trim();
        }

        return null;
    }

    private String resolveAttributionLabel(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object attributionLabel = metadata.get("knowledgeSourceAttributionLabel");
        if (attributionLabel instanceof String label && StringUtils.hasText(label)) {
            return label.trim();
        }
        Object sourceId = metadata.get("knowledgeSourceId");
        if (sourceId instanceof String source && StringUtils.hasText(source)) {
            return source.trim();
        }
        return null;
    }
    
    private String resolveEmbeddingQuery(RAGRequest request, String processedQuery) {
        String explicit = extractEmbeddingQuery(request != null ? request.getMetadata() : null);
        if (StringUtils.hasText(explicit)) {
            return explicit;
        }

        String optimized = extractOptimizedQuery(request != null ? request.getMetadata() : null);
        if (StringUtils.hasText(optimized)) {
            return optimized;
        }
        return processedQuery;
    }

    private String extractEmbeddingQuery(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object candidate = metadata.get(METADATA_KEY_EMBEDDING_QUERY);
        if (candidate instanceof String str && StringUtils.hasText(str)) {
            return str;
        }
        return null;
    }

    private String extractUserQuery(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object candidate = metadata.get(METADATA_KEY_USER_QUERY);
        if (candidate instanceof String str && StringUtils.hasText(str)) {
            return str;
        }
        return null;
    }

    private String extractOptimizedQuery(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object candidate = metadata.get(METADATA_KEY_OPTIMIZED_QUERY);
        if (candidate instanceof String str && StringUtils.hasText(str)) {
            return str;
        }
        return null;
    }

    private Map<String, Object> normalizeMetadata(Object metadata) {
        if (metadata instanceof Map<?, ?> rawMap) {
            return rawMap.entrySet().stream()
                .collect(Collectors.toMap(entry -> String.valueOf(entry.getKey()), Map.Entry::getValue));
        }
        if (metadata instanceof String metadataJson && !metadataJson.trim().isEmpty()) {
            try {
                Map<String, Object> parsed = OBJECT_MAPPER.readValue(metadataJson, MAP_TYPE_REFERENCE);
                return parsed != null ? parsed : Collections.emptyMap();
            } catch (Exception e) {
                log.debug("Unable to parse metadata JSON: {}", metadataJson, e);
                return Collections.singletonMap(RESULT_KEY_RAW, metadataJson);
            }
        }
        return Collections.emptyMap();
    }

    private boolean matchesFilters(Map<String, Object> metadata, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }

        Map<String, Object> safeMetadata = metadata != null ? metadata : Collections.emptyMap();

        return filters.entrySet().stream()
            .allMatch(entry -> matchesFilter(safeMetadata, entry.getKey(), entry.getValue()));
    }

    private boolean matchesFilter(Map<String, Object> metadata, String key, Object expected) {
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
            if (max != null && actualValue > max) {
                return false;
            }
            return true;
        }

        return valuesEqual(actual, expected);
    }

    private boolean valuesEqual(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return String.valueOf(actual).equalsIgnoreCase(String.valueOf(expected));
    }

    private Double parseDouble(Object value) {
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
    
    private double extractScore(Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return 0.0;
        }
        Object score = result.get(RESULT_KEY_SCORE);
        if (score instanceof Number number) {
            return number.doubleValue();
        }
        Double parsed = parseDouble(score);
        return parsed != null ? parsed : 0.0;
    }

    private Map<String, Object> buildAggregatedMetadata(Map<String, Object> requestMetadata,
            String embeddingQuery) {
        Map<String, Object> aggregatedMetadata = new HashMap<>();
        if (requestMetadata != null) {
            aggregatedMetadata.putAll(requestMetadata);
        }
        aggregatedMetadata.put(METADATA_KEY_OPTIMIZED_QUERY_PROVIDED, 
            extractOptimizedQuery(requestMetadata) != null);
        aggregatedMetadata.put(METADATA_KEY_EMBEDDING_QUERY, embeddingQuery);
        return aggregatedMetadata;
    }

    private void appendSearchSourceMetadata(Map<String, Object> aggregatedMetadata, AISearchResponse searchResponse) {
        if (searchResponse == null || searchResponse.getResults() == null || searchResponse.getResults().isEmpty()) {
            return;
        }
        LinkedHashSet<String> sourceIds = new LinkedHashSet<>();
        LinkedHashSet<String> sourceTypes = new LinkedHashSet<>();
        LinkedHashSet<String> adapterTypes = new LinkedHashSet<>();

        for (Map<String, Object> result : searchResponse.getResults()) {
            Map<String, Object> metadata = normalizeMetadata(result.get(RESULT_KEY_METADATA));
            addIfPresent(sourceIds, metadata.get("knowledgeSourceId"));
            addIfPresent(sourceTypes, metadata.get("knowledgeSourceType"));
            addIfPresent(adapterTypes, metadata.get("knowledgeSourceAdapterType"));
        }

        if (!sourceIds.isEmpty()) {
            aggregatedMetadata.put(METADATA_KEY_SEARCH_SOURCE_COUNT, sourceIds.size());
            aggregatedMetadata.put(METADATA_KEY_SEARCH_SOURCE_IDS, List.copyOf(sourceIds));
        }
        if (!sourceTypes.isEmpty()) {
            aggregatedMetadata.put(METADATA_KEY_SEARCH_SOURCE_TYPES, List.copyOf(sourceTypes));
        }
        if (!adapterTypes.isEmpty()) {
            aggregatedMetadata.put(METADATA_KEY_SEARCH_SOURCE_ADAPTER_TYPES, List.copyOf(adapterTypes));
        }
    }

    private void appendSearchExecutionMetadata(Map<String, Object> aggregatedMetadata, SearchExecutionAggregate searchExecution) {
        if (aggregatedMetadata == null || searchExecution == null) {
            return;
        }
        if (!searchExecution.sourceDiagnostics().isEmpty()) {
            aggregatedMetadata.put(METADATA_KEY_SEARCH_SOURCE_DIAGNOSTICS, searchExecution.sourceDiagnostics());
        }
        aggregatedMetadata.put(METADATA_KEY_SEARCH_SOURCE_COUNT, searchExecution.resolvedSourceCount());
        aggregatedMetadata.put(METADATA_KEY_SEARCH_SOURCE_ELIGIBLE_COUNT, searchExecution.eligibleSourceCount());
        aggregatedMetadata.put(METADATA_KEY_SEARCH_SOURCE_ATTEMPTED_COUNT, searchExecution.attemptedSourceCount());
        aggregatedMetadata.put(METADATA_KEY_SEARCH_SOURCE_SUCCEEDED_COUNT, searchExecution.succeededSourceCount());
        aggregatedMetadata.put(METADATA_KEY_SEARCH_SOURCE_FAILED_COUNT, searchExecution.failedSourceCount());
        aggregatedMetadata.put(METADATA_KEY_SEARCH_SOURCE_SKIPPED_COUNT, searchExecution.skippedSourceCount());
        aggregatedMetadata.put(METADATA_KEY_SEARCH_SOURCES_DEGRADED, searchExecution.degraded());
    }

    private void addIfPresent(LinkedHashSet<String> target, Object value) {
        if (value instanceof String text && StringUtils.hasText(text)) {
            target.add(text.trim());
        }
    }

    private Map<String, Object> baseSearchSourceDiagnostic(SearchSource source) {
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        putIfText(diagnostic, "sourceId", source.sourceId());
        putIfText(diagnostic, "sourceType", source.sourceType());
        putIfText(diagnostic, "adapterType", source.adapterType());
        return diagnostic;
    }

    private Map<String, Object> immutableDiagnostic(Map<String, Object> diagnostic) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(diagnostic));
    }

    private void putIfText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private String resultDedupKey(Map<String, Object> result) {
        Map<String, Object> metadata = normalizeMetadata(result != null ? result.get(RESULT_KEY_METADATA) : null);
        String sourceId = metadata.get("knowledgeSourceId") instanceof String text && StringUtils.hasText(text)
            ? text.trim()
            : "";
        String id = result != null && result.get(RESULT_KEY_ID) instanceof String text && StringUtils.hasText(text)
            ? text.trim()
            : "";
        String content = result != null && result.get(RESULT_KEY_CONTENT) instanceof String text && StringUtils.hasText(text)
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

    private String buildContextFromDocuments(List<RAGResponse.RAGDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return NO_CONTEXT_MESSAGE;
        }

        StringBuilder context = new StringBuilder();
        context.append(CONTEXT_HEADER);

        for (int i = 0; i < documents.size(); i++) {
            RAGResponse.RAGDocument doc = documents.get(i);
            String content = doc != null ? doc.getContent() : null;
            double score = doc != null && doc.getScore() != null ? doc.getScore() : 0.0;

            String id = doc != null ? doc.getId() : null;
            String vectorSpace = null;
            if (doc != null && doc.getMetadata() != null) {
                Object vs = doc.getMetadata().get(RESULT_KEY_VECTOR_SPACE);
                if (vs instanceof String vsText && StringUtils.hasText(vsText)) {
                    vectorSpace = vsText.trim();
                }
            }
            if (!StringUtils.hasText(vectorSpace) && doc != null && StringUtils.hasText(doc.getType())) {
                vectorSpace = doc.getType().trim();
            }

            String header = "";
            if (StringUtils.hasText(vectorSpace) || StringUtils.hasText(id)) {
                header = "["
                    + (StringUtils.hasText(vectorSpace) ? "vectorSpace=" + vectorSpace : "")
                    + (StringUtils.hasText(vectorSpace) && StringUtils.hasText(id) ? " " : "")
                    + (StringUtils.hasText(id) ? "id=" + id : "")
                    + "] ";
            }

            context.append(String.format("%d. %s%s (Score: %.3f)\n",
                i + 1,
                header,
                content != null ? content : "",
                score));
        }

        return context.toString();
    }

    private record SearchExecutionAggregate(AISearchResponse response,
                                            List<Map<String, Object>> sourceDiagnostics,
                                            int resolvedSourceCount,
                                            int eligibleSourceCount,
                                            int attemptedSourceCount,
                                            int succeededSourceCount,
                                            int failedSourceCount,
                                            int skippedSourceCount,
                                            boolean degraded) {

        private static SearchExecutionAggregate singleResponse(AISearchResponse response) {
            return new SearchExecutionAggregate(
                response,
                List.of(),
                0,
                0,
                0,
                0,
                0,
                0,
                false
            );
        }

        private static SearchExecutionAggregate empty(AISearchRequest request) {
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
                false
            );
        }
    }
}
