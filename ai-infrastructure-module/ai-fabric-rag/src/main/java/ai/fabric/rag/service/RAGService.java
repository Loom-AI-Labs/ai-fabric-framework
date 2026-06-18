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
import ai.fabric.rag.source.SearchSourceRegistry;
import ai.fabric.spi.RAGProvider;
import ai.fabric.vector.VectorDatabase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
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
 */
@Slf4j
public class RAGService implements RAGProvider {

    private static final String PROVIDER_NAME = "default-rag-service";

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

    private static final double DEFAULT_SEARCH_THRESHOLD = 0.7;
    private static final int DEFAULT_RESULT_LIMIT = 10;

    private final AIProviderConfig config;
    private final AIEmbeddingService embeddingService;
    private final VectorDatabaseService vectorDatabaseService;
    private final VectorDatabase vectorDatabase;
    private final AISearchService searchService;
    private final RAGSearchExecutor searchExecutor;
    private final RAGDocumentMapper documentMapper;

    public RAGService(AIProviderConfig config,
                      AIEmbeddingService embeddingService,
                      VectorDatabaseService vectorDatabaseService,
                      VectorDatabase vectorDatabase,
                      AISearchService searchService,
                      SearchSourceRegistry searchSourceRegistry) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.embeddingService = Objects.requireNonNull(embeddingService, "embeddingService must not be null");
        this.vectorDatabaseService = Objects.requireNonNull(vectorDatabaseService, "vectorDatabaseService must not be null");
        this.vectorDatabase = Objects.requireNonNull(vectorDatabase, "vectorDatabase must not be null");
        this.searchService = Objects.requireNonNull(searchService, "searchService must not be null");
        this.searchExecutor = new RAGSearchExecutor(vectorDatabaseService, searchService, searchSourceRegistry);
        this.documentMapper = new RAGDocumentMapper();
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

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

            AIEmbeddingResponse embeddingResponse = embeddingService.generateEmbedding(embeddingRequest);
            vectorDatabaseService.storeVector(
                entityType,
                entityId,
                content,
                embeddingResponse.getEmbedding(),
                metadata
            );

            log.debug("Successfully indexed content for entity {} of type {}", entityId, entityType);
        } catch (Exception e) {
            log.error("Error indexing content for entity {} of type {}", entityId, entityType, e);
            throw new AIServiceException("Failed to index content", e);
        }
    }

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

    @Override
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalIndexed", vectorDatabaseService.getStatistics());
        stats.put("vectorDatabase", vectorDatabase.getStatistics());
        return stats;
    }

    @Override
    public RAGResponse performRag(RAGRequest request) {
        if (request == null) {
            return failedRetrievalResponse(null, "RAG request must not be null");
        }

        try {
            long startTime = System.currentTimeMillis();
            String processedQuery = request.getQuery();
            String embeddingQuery = RAGMetadataSupport.resolveEmbeddingQuery(request.getMetadata(), processedQuery);

            log.debug("Performing RAG operation (entityType={}, requestId={})",
                request.getEntityType(),
                request.getRequestId());

            AIEmbeddingService.EmbeddingExecution embeddingExecution = executeEmbedding(embeddingQuery, false);
            AISearchRequest searchRequest = buildSearchRequest(request, embeddingQuery, true);
            RAGSearchExecutor.SearchExecutionAggregate searchExecution = searchExecutor.performSearch(
                embeddingExecution.response().getEmbedding(),
                request,
                searchRequest,
                false
            );

            AISearchResponse searchResponse = searchExecution.response();
            List<RAGResponse.RAGDocument> documents = documentMapper.toFilteredDocuments(
                safeResults(searchResponse),
                request.getFilters()
            );
            String context = documentMapper.buildContextFromDocuments(documents);
            long totalProcessingTimeMs = System.currentTimeMillis() - startTime;
            Map<String, Object> metadata = responseMetadata(
                request,
                embeddingQuery,
                embeddingExecution,
                searchResponse,
                searchExecution,
                totalProcessingTimeMs
            );

            String originalUserQuery = RAGMetadataSupport.extractUserQuery(request.getMetadata());

            return RAGResponse.builder()
                .context(context)
                .documents(documents)
                .totalDocuments(safeTotalResults(searchResponse))
                .usedDocuments(documents.size())
                .relevanceScores(documents.stream()
                    .map(RAGResponse.RAGDocument::getScore)
                    .collect(Collectors.toList()))
                .success(true)
                .totalResults(safeTotalResults(searchResponse))
                .returnedResults(documents.size())
                .maxScore(documents.stream()
                    .mapToDouble(doc -> doc.getScore() != null ? doc.getScore() : 0.0)
                    .max().orElse(0.0))
                .averageScore(documents.stream()
                    .mapToDouble(doc -> doc.getScore() != null ? doc.getScore() : 0.0)
                    .average().orElse(0.0))
                .processingTimeMs(totalProcessingTimeMs)
                .requestId(request.getRequestId())
                .originalQuery(StringUtils.hasText(originalUserQuery) ? originalUserQuery : processedQuery)
                .entityType(request.getEntityType())
                .model(config.resolveEmbeddingDefaults().model())
                .timestamp(LocalDateTime.now())
                .metadata(Collections.unmodifiableMap(metadata))
                .build();
        } catch (Exception e) {
            log.error("Error performing RAG operation", e);
            return failedRetrievalResponse(request, "Failed to perform RAG operation: " + e.getMessage());
        }
    }

    @Override
    public RAGResponse performRAGQuery(RAGRequest request) {
        if (request == null) {
            return failedQueryResponse(null, "RAG request must not be null");
        }

        try {
            String processedQuery = request.getQuery();
            String embeddingQuery = RAGMetadataSupport.resolveEmbeddingQuery(request.getMetadata(), processedQuery);

            log.debug("Performing RAG query (entityType={}, requestId={})",
                request.getEntityType(),
                request.getRequestId());

            long startTime = System.currentTimeMillis();
            AIEmbeddingService.EmbeddingExecution embeddingExecution = executeEmbedding(embeddingQuery, true);
            AISearchRequest searchRequest = buildSearchRequest(request, embeddingQuery, false);
            RAGSearchExecutor.SearchExecutionAggregate searchExecution = searchExecutor.performSearch(
                embeddingExecution.response().getEmbedding(),
                request,
                searchRequest,
                true
            );

            AISearchResponse searchResponse = searchExecution.response();
            List<RAGResponse.RAGDocument> documents = documentMapper.toFilteredDocuments(
                safeResults(searchResponse),
                request.getFilters()
            );
            String context = documentMapper.buildContextFromDocuments(documents);
            long processingTime = System.currentTimeMillis() - startTime;
            Map<String, Object> metadata = responseMetadata(
                request,
                embeddingQuery,
                embeddingExecution,
                searchResponse,
                searchExecution,
                processingTime
            );

            String originalUserQuery = RAGMetadataSupport.extractUserQuery(request.getMetadata());

            return RAGResponse.builder()
                .context(context)
                .documents(documents)
                .totalDocuments(safeTotalResults(searchResponse))
                .usedDocuments(documents.size())
                .confidenceScore(calculateConfidence(documents))
                .relevanceScores(documents.stream()
                    .map(RAGResponse.RAGDocument::getSimilarity)
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
                .metadata(Collections.unmodifiableMap(metadata))
                .build();
        } catch (Exception e) {
            log.error("Error performing RAG query", e);
            return failedQueryResponse(request, e.getMessage());
        }
    }

    private AIEmbeddingService.EmbeddingExecution executeEmbedding(String embeddingQuery, boolean includeModel) {
        AIEmbeddingRequest.AIEmbeddingRequestBuilder builder = AIEmbeddingRequest.builder()
            .text(embeddingQuery);
        if (includeModel) {
            builder.model(config.resolveEmbeddingDefaults().model());
        }
        return embeddingService.executeEmbedding(builder.build());
    }

    private AISearchRequest buildSearchRequest(RAGRequest request, String embeddingQuery, boolean includeContextAndMetadata) {
        AISearchRequest.AISearchRequestBuilder builder = AISearchRequest.builder()
            .query(embeddingQuery)
            .entityType(request.getEntityType())
            .limit(effectiveLimit(request))
            .threshold(effectiveThreshold(request));

        if (includeContextAndMetadata) {
            builder
                .context(request.getContext() != null ? request.getContext().toString() : null)
                .filters(request.getFilters() != null ? request.getFilters().toString() : null)
                .metadata(request.getFilters());
        }

        return builder.build();
    }

    private Map<String, Object> responseMetadata(RAGRequest request,
                                                 String embeddingQuery,
                                                 AIEmbeddingService.EmbeddingExecution embeddingExecution,
                                                 AISearchResponse searchResponse,
                                                 RAGSearchExecutor.SearchExecutionAggregate searchExecution,
                                                 long processingTimeMs) {
        Map<String, Object> metadata = RAGMetadataSupport.buildAggregatedMetadata(request.getMetadata(), embeddingQuery);
        metadata.put(METADATA_KEY_RAG_TOTAL_PROCESSING_TIME_MS, processingTimeMs);
        metadata.put(METADATA_KEY_EMBEDDING_PROCESSING_TIME_MS, serviceProcessingTime(embeddingExecution));
        metadata.put(METADATA_KEY_EMBEDDING_PROVIDER_PROCESSING_TIME_MS, embeddingExecution.providerProcessingTimeMs());
        metadata.put(METADATA_KEY_EMBEDDING_CACHE_HIT, embeddingExecution.cacheHit());
        metadata.put(METADATA_KEY_EMBEDDING_PROVIDER_NAME, embeddingExecution.providerName());
        metadata.put(METADATA_KEY_EMBEDDING_MODEL, embeddingExecution.effectiveModel());
        metadata.put(METADATA_KEY_SEARCH_PROCESSING_TIME_MS, searchResponse.getProcessingTimeMs());
        if (StringUtils.hasText(searchResponse.getRequestId())) {
            metadata.put(METADATA_KEY_SEARCH_REQUEST_ID, searchResponse.getRequestId());
        }
        if (searchResponse.getMaxScore() != null) {
            metadata.put(METADATA_KEY_SEARCH_MAX_SCORE, searchResponse.getMaxScore());
        }
        appendSearchSourceMetadata(metadata, searchResponse);
        appendSearchExecutionMetadata(metadata, searchExecution);
        return metadata;
    }

    private long serviceProcessingTime(AIEmbeddingService.EmbeddingExecution embeddingExecution) {
        return embeddingExecution.serviceProcessingTimeMs() != null
            ? embeddingExecution.serviceProcessingTimeMs()
            : 0L;
    }

    private void appendSearchSourceMetadata(Map<String, Object> metadata, AISearchResponse searchResponse) {
        if (searchResponse == null || searchResponse.getResults() == null || searchResponse.getResults().isEmpty()) {
            return;
        }
        LinkedHashSet<String> sourceIds = new LinkedHashSet<>();
        LinkedHashSet<String> sourceTypes = new LinkedHashSet<>();
        LinkedHashSet<String> adapterTypes = new LinkedHashSet<>();

        for (Map<String, Object> result : searchResponse.getResults()) {
            Map<String, Object> resultMetadata = RAGMetadataSupport.normalizeMetadata(
                result.get(RAGDocumentMapper.RESULT_KEY_METADATA)
            );
            addIfPresent(sourceIds, resultMetadata.get("knowledgeSourceId"));
            addIfPresent(sourceTypes, resultMetadata.get("knowledgeSourceType"));
            addIfPresent(adapterTypes, resultMetadata.get("knowledgeSourceAdapterType"));
        }

        if (!sourceIds.isEmpty()) {
            metadata.put(METADATA_KEY_SEARCH_SOURCE_COUNT, sourceIds.size());
            metadata.put(METADATA_KEY_SEARCH_SOURCE_IDS, List.copyOf(sourceIds));
        }
        if (!sourceTypes.isEmpty()) {
            metadata.put(METADATA_KEY_SEARCH_SOURCE_TYPES, List.copyOf(sourceTypes));
        }
        if (!adapterTypes.isEmpty()) {
            metadata.put(METADATA_KEY_SEARCH_SOURCE_ADAPTER_TYPES, List.copyOf(adapterTypes));
        }
    }

    private void appendSearchExecutionMetadata(Map<String, Object> metadata,
                                               RAGSearchExecutor.SearchExecutionAggregate searchExecution) {
        if (metadata == null || searchExecution == null) {
            return;
        }
        if (!searchExecution.sourceDiagnostics().isEmpty()) {
            metadata.put(METADATA_KEY_SEARCH_SOURCE_DIAGNOSTICS, searchExecution.sourceDiagnostics());
        }
        metadata.put(METADATA_KEY_SEARCH_SOURCE_COUNT, searchExecution.resolvedSourceCount());
        metadata.put(METADATA_KEY_SEARCH_SOURCE_ELIGIBLE_COUNT, searchExecution.eligibleSourceCount());
        metadata.put(METADATA_KEY_SEARCH_SOURCE_ATTEMPTED_COUNT, searchExecution.attemptedSourceCount());
        metadata.put(METADATA_KEY_SEARCH_SOURCE_SUCCEEDED_COUNT, searchExecution.succeededSourceCount());
        metadata.put(METADATA_KEY_SEARCH_SOURCE_FAILED_COUNT, searchExecution.failedSourceCount());
        metadata.put(METADATA_KEY_SEARCH_SOURCE_SKIPPED_COUNT, searchExecution.skippedSourceCount());
        metadata.put(METADATA_KEY_SEARCH_SOURCES_DEGRADED, searchExecution.degraded());
    }

    private void addIfPresent(LinkedHashSet<String> target, Object value) {
        if (value instanceof String text && StringUtils.hasText(text)) {
            target.add(text.trim());
        }
    }

    private double calculateConfidence(List<RAGResponse.RAGDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return 0.0;
        }

        double avgScore = documents.stream()
            .mapToDouble(doc -> doc.getScore() != null ? doc.getScore() : 0.0)
            .average()
            .orElse(0.0);
        double avgSimilarity = documents.stream()
            .mapToDouble(doc -> doc.getSimilarity() != null ? doc.getSimilarity() : 0.0)
            .average()
            .orElse(0.0);
        return (avgScore + avgSimilarity) / 2.0;
    }

    private List<Map<String, Object>> safeResults(AISearchResponse searchResponse) {
        return searchResponse != null && searchResponse.getResults() != null
            ? searchResponse.getResults()
            : List.of();
    }

    private int safeTotalResults(AISearchResponse searchResponse) {
        return searchResponse != null && searchResponse.getTotalResults() != null
            ? searchResponse.getTotalResults()
            : safeResults(searchResponse).size();
    }

    private int effectiveLimit(RAGRequest request) {
        return request.getLimit() != null && request.getLimit() > 0
            ? request.getLimit()
            : DEFAULT_RESULT_LIMIT;
    }

    private double effectiveThreshold(RAGRequest request) {
        return request.getThreshold() != null
            ? request.getThreshold()
            : DEFAULT_SEARCH_THRESHOLD;
    }

    private RAGResponse failedRetrievalResponse(RAGRequest request, String message) {
        return RAGResponse.builder()
            .success(false)
            .documents(List.of())
            .context("")
            .totalDocuments(0)
            .usedDocuments(0)
            .requestId(request != null ? request.getRequestId() : null)
            .errorMessage(message)
            .build();
    }

    private RAGResponse failedQueryResponse(RAGRequest request, String message) {
        return RAGResponse.builder()
            .context("")
            .documents(List.of())
            .totalDocuments(0)
            .usedDocuments(0)
            .confidenceScore(0.0)
            .relevanceScores(List.of())
            .processingTimeMs(0L)
            .requestId(request != null ? request.getRequestId() : null)
            .model(config.resolveEmbeddingDefaults().model())
            .success(false)
            .errorMessage(message)
            .build();
    }
}
