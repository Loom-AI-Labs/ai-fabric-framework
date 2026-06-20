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
import ai.fabric.dto.VectorRecord;
import ai.fabric.exception.AIServiceException;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.rag.config.RAGProperties;
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
import java.util.Optional;
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
    private static final String METADATA_KEY_SEARCH_EXECUTION_PATH = "searchExecutionPath";
    private static final String METADATA_KEY_HYBRID_SEARCH_REQUESTED = "hybridSearchRequested";
    private static final String METADATA_KEY_HYBRID_SEARCH_USED = "hybridSearchUsed";
    private static final String METADATA_KEY_HYBRID_SEARCH_MODE = "hybridSearchMode";
    private static final String METADATA_KEY_VECTOR_PROVIDER_SUPPORTS_HYBRID_SEARCH = "vectorProviderSupportsHybridSearch";
    private static final String METADATA_KEY_CONTEXTUAL_SEARCH_USED = "contextualSearchUsed";

    private static final double DEFAULT_SEARCH_THRESHOLD = 0.7;
    private static final int DEFAULT_RESULT_LIMIT = 10;

    private final AIProviderConfig config;
    private final AIEmbeddingService embeddingService;
    private final VectorDatabaseService vectorDatabaseService;
    private final VectorDatabase vectorDatabase;
    private final AISearchService searchService;
    private final RAGSearchExecutor searchExecutor;
    private final RAGDocumentMapper documentMapper;
    private final int defaultResultLimit;
    private final double defaultSearchThreshold;
    private final boolean defaultHybridSearch;
    private final boolean defaultContextualSearch;
    private final int maxIndexContentLength;

    public RAGService(AIProviderConfig config,
                      AIEmbeddingService embeddingService,
                      VectorDatabaseService vectorDatabaseService,
                      VectorDatabase vectorDatabase,
                      AISearchService searchService,
                      SearchSourceRegistry searchSourceRegistry) {
        this(config, embeddingService, vectorDatabaseService, vectorDatabase, searchService, searchSourceRegistry, legacyDefaults());
    }

    public RAGService(AIProviderConfig config,
                      AIEmbeddingService embeddingService,
                      VectorDatabaseService vectorDatabaseService,
                      VectorDatabase vectorDatabase,
                      AISearchService searchService,
                      SearchSourceRegistry searchSourceRegistry,
                      RAGProperties properties) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.embeddingService = Objects.requireNonNull(embeddingService, "embeddingService must not be null");
        this.vectorDatabaseService = Objects.requireNonNull(vectorDatabaseService, "vectorDatabaseService must not be null");
        this.vectorDatabase = Objects.requireNonNull(vectorDatabase, "vectorDatabase must not be null");
        this.searchService = Objects.requireNonNull(searchService, "searchService must not be null");
        this.searchExecutor = new RAGSearchExecutor(vectorDatabaseService, searchService, searchSourceRegistry);
        this.documentMapper = new RAGDocumentMapper();
        RAGProperties resolvedProperties = properties != null ? properties : legacyDefaults();
        this.defaultResultLimit = normalizeDefaultLimit(resolvedProperties.getDefaultLimit());
        this.defaultSearchThreshold = normalizeDefaultThreshold(resolvedProperties.getDefaultThreshold());
        this.defaultHybridSearch = resolvedProperties.isEnableHybridSearch();
        this.defaultContextualSearch = resolvedProperties.isEnableContextualSearch();
        this.maxIndexContentLength = normalizeMaxIndexContentLength(resolvedProperties);
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public void indexContent(String entityType, String entityId, String content, Map<String, Object> metadata) {
        try {
            log.debug("Indexing content for entity {} of type {}", entityId, entityType);
            String indexedContent = truncateIndexContent(content);

            AIEmbeddingRequest embeddingRequest = AIEmbeddingRequest.builder()
                .text(indexedContent)
                .entityType(entityType)
                .entityId(entityId)
                .metadata(metadata != null ? metadata.toString() : null)
                .build();

            AIEmbeddingResponse embeddingResponse = embeddingService.generateEmbedding(embeddingRequest);
            upsertIndexedVector(
                entityType,
                entityId,
                indexedContent,
                embeddingResponse.getEmbedding(),
                metadata
            );

            log.debug("Successfully indexed content for entity {} of type {}", entityId, entityType);
        } catch (Exception e) {
            log.error("Error indexing content for entity {} of type {}", entityId, entityType, e);
            throw new AIServiceException("Failed to index content", e);
        }
    }

    private void upsertIndexedVector(String entityType,
                                     String entityId,
                                     String content,
                                     List<Double> embedding,
                                     Map<String, Object> metadata) {
        Optional<VectorRecord> existing = vectorDatabaseService.getVectorByEntity(entityType, entityId);
        if (existing.isPresent()) {
            String vectorId = existing.get().getVectorId();
            if (StringUtils.hasText(vectorId)) {
                boolean updated = vectorDatabaseService.updateVector(vectorId, entityType, entityId, content, embedding, metadata);
                if (updated) {
                    log.debug("Updated existing vector {} for entity {} of type {}", vectorId, entityId, entityType);
                    return;
                }
                log.warn("Existing vector {} for entity {} of type {} was not updated; storing a replacement",
                    vectorId,
                    entityId,
                    entityType);
                vectorDatabaseService.removeVector(entityType, entityId);
            } else {
                log.warn("Existing vector for entity {} of type {} has no vector id; removing by entity before re-indexing",
                    entityId,
                    entityType);
                if (!vectorDatabaseService.removeVector(entityType, entityId)) {
                    throw new AIServiceException("Cannot safely re-index content because existing vector has no vector id");
                }
            }
        }

        vectorDatabaseService.storeVector(entityType, entityId, content, embedding, metadata);
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
            RAGRequest effectiveRequest = withEffectiveDefaults(request);
            String processedQuery = effectiveRequest.getQuery();
            String embeddingQuery = RAGMetadataSupport.resolveEmbeddingQuery(effectiveRequest.getMetadata(), processedQuery);

            log.debug("Performing RAG operation (entityType={}, requestId={})",
                effectiveRequest.getEntityType(),
                effectiveRequest.getRequestId());

            AIEmbeddingService.EmbeddingExecution embeddingExecution = executeEmbedding(embeddingQuery, false);
            AISearchRequest searchRequest = buildSearchRequest(effectiveRequest, embeddingQuery, true);
            RAGSearchExecutor.SearchExecutionAggregate searchExecution = searchExecutor.performSearch(
                embeddingExecution.response().getEmbedding(),
                effectiveRequest,
                searchRequest,
                false
            );

            AISearchResponse searchResponse = searchExecution.response();
            List<RAGResponse.RAGDocument> documents = documentMapper.toFilteredDocuments(
                safeResults(searchResponse),
                effectiveRequest.getFilters()
            );
            String context = documentMapper.buildContextFromDocuments(documents);
            long totalProcessingTimeMs = System.currentTimeMillis() - startTime;
            Map<String, Object> metadata = responseMetadata(
                effectiveRequest,
                embeddingQuery,
                embeddingExecution,
                searchResponse,
                searchExecution,
                totalProcessingTimeMs
            );

            String originalUserQuery = RAGMetadataSupport.extractUserQuery(effectiveRequest.getMetadata());

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
                .requestId(effectiveRequest.getRequestId())
                .originalQuery(StringUtils.hasText(originalUserQuery) ? originalUserQuery : processedQuery)
                .entityType(effectiveRequest.getEntityType())
                .model(config.resolveEmbeddingDefaults().model())
                .timestamp(LocalDateTime.now())
                .hybridSearchUsed(searchExecution.hybridSearchUsed())
                .contextualSearchUsed(searchExecution.contextualSearchUsed())
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
            RAGRequest effectiveRequest = withEffectiveDefaults(request);
            String processedQuery = effectiveRequest.getQuery();
            String embeddingQuery = RAGMetadataSupport.resolveEmbeddingQuery(effectiveRequest.getMetadata(), processedQuery);

            log.debug("Performing RAG query (entityType={}, requestId={})",
                effectiveRequest.getEntityType(),
                effectiveRequest.getRequestId());

            long startTime = System.currentTimeMillis();
            AIEmbeddingService.EmbeddingExecution embeddingExecution = executeEmbedding(embeddingQuery, true);
            AISearchRequest searchRequest = buildSearchRequest(effectiveRequest, embeddingQuery, false);
            RAGSearchExecutor.SearchExecutionAggregate searchExecution = searchExecutor.performSearch(
                embeddingExecution.response().getEmbedding(),
                effectiveRequest,
                searchRequest,
                true
            );

            AISearchResponse searchResponse = searchExecution.response();
            List<RAGResponse.RAGDocument> documents = documentMapper.toFilteredDocuments(
                safeResults(searchResponse),
                effectiveRequest.getFilters()
            );
            String context = documentMapper.buildContextFromDocuments(documents);
            long processingTime = System.currentTimeMillis() - startTime;
            Map<String, Object> metadata = responseMetadata(
                effectiveRequest,
                embeddingQuery,
                embeddingExecution,
                searchResponse,
                searchExecution,
                processingTime
            );

            String originalUserQuery = RAGMetadataSupport.extractUserQuery(effectiveRequest.getMetadata());

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
                .requestId(effectiveRequest.getRequestId())
                .model(config.resolveEmbeddingDefaults().model())
                .success(true)
                .hybridSearchUsed(searchExecution.hybridSearchUsed())
                .contextualSearchUsed(searchExecution.contextualSearchUsed())
                .originalQuery(StringUtils.hasText(originalUserQuery) ? originalUserQuery : processedQuery)
                .entityType(effectiveRequest.getEntityType())
                .searchedCategories(effectiveRequest.getCategories())
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
        metadata.put(METADATA_KEY_SEARCH_EXECUTION_PATH, searchExecution.searchExecutionPath());
        metadata.put(METADATA_KEY_HYBRID_SEARCH_REQUESTED, searchExecution.hybridSearchRequested());
        metadata.put(METADATA_KEY_HYBRID_SEARCH_USED, searchExecution.hybridSearchUsed());
        metadata.put(METADATA_KEY_HYBRID_SEARCH_MODE, searchExecution.hybridSearchMode());
        metadata.put(METADATA_KEY_VECTOR_PROVIDER_SUPPORTS_HYBRID_SEARCH,
            searchExecution.vectorProviderSupportsHybridSearch());
        metadata.put(METADATA_KEY_CONTEXTUAL_SEARCH_USED, searchExecution.contextualSearchUsed());
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
            : defaultResultLimit;
    }

    private double effectiveThreshold(RAGRequest request) {
        return request.getThreshold() != null
            ? request.getThreshold()
            : defaultSearchThreshold;
    }

    private RAGRequest withEffectiveDefaults(RAGRequest request) {
        return RAGRequest.builder()
            .query(request.getQuery())
            .entityType(request.getEntityType())
            .limit(effectiveLimit(request))
            .threshold(effectiveThreshold(request))
            .context(request.getContext())
            .filters(request.getFilters())
            .metadata(request.getMetadata())
            .authContext(request.getAuthContext())
            .includeEmbeddings(request.getIncludeEmbeddings())
            .includeMetadata(request.getIncludeMetadata())
            .language(request.getLanguage())
            .searchFields(request.getSearchFields())
            .sortBy(request.getSortBy())
            .timeoutMs(request.getTimeoutMs())
            .useHybridSearch(request.getUseHybridSearch())
            .boostFactors(request.getBoostFactors())
            .scoringFunction(request.getScoringFunction())
            .requestId(request.getRequestId())
            .timestamp(request.getTimestamp())
            .priority(request.getPriority())
            .cacheable(request.getCacheable())
            .cacheTtlSeconds(request.getCacheTtlSeconds())
            .enableHybridSearch(request.getEnableHybridSearch() != null
                ? request.getEnableHybridSearch()
                : defaultHybridSearch)
            .enableContextualSearch(request.getEnableContextualSearch() != null
                ? request.getEnableContextualSearch()
                : defaultContextualSearch)
            .categories(request.getCategories())
            .build();
    }

    private String truncateIndexContent(String content) {
        if (content == null || content.length() <= maxIndexContentLength) {
            return content;
        }
        log.debug("Truncating indexed content from {} to {} characters", content.length(), maxIndexContentLength);
        return content.substring(0, maxIndexContentLength);
    }

    private static RAGProperties legacyDefaults() {
        RAGProperties properties = new RAGProperties();
        properties.setDefaultLimit(DEFAULT_RESULT_LIMIT);
        properties.setDefaultThreshold(DEFAULT_SEARCH_THRESHOLD);
        properties.setEnableHybridSearch(true);
        properties.setEnableContextualSearch(true);
        properties.getIndexing().setMaxContentLength(10000);
        return properties;
    }

    private static int normalizeDefaultLimit(int value) {
        return value > 0 ? value : DEFAULT_RESULT_LIMIT;
    }

    private static double normalizeDefaultThreshold(double value) {
        if (value < 0.0d || value > 1.0d) {
            return DEFAULT_SEARCH_THRESHOLD;
        }
        return value;
    }

    private static int normalizeMaxIndexContentLength(RAGProperties properties) {
        if (properties == null || properties.getIndexing() == null || properties.getIndexing().getMaxContentLength() <= 0) {
            return 10000;
        }
        return properties.getIndexing().getMaxContentLength();
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
