package ai.fabric.relationship.service;

import ai.fabric.core.AIEmbeddingService;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.RAGResponse;
import ai.fabric.relationship.cache.QueryCache;
import ai.fabric.relationship.config.RelationshipModuleMetadata;
import ai.fabric.relationship.config.RelationshipQueryProperties;
import ai.fabric.relationship.dto.JpqlQuery;
import ai.fabric.relationship.dto.RelationshipQueryPlan;
import ai.fabric.relationship.model.QueryMode;
import ai.fabric.relationship.model.QueryOptions;
import ai.fabric.relationship.model.ReturnMode;
import ai.fabric.relationship.validation.RelationshipQueryValidator;
import ai.fabric.relationship.metrics.QueryMetrics;
import ai.fabric.rag.VectorDatabaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Primary entrypoint that orchestrates LLM planning, JPQL execution, and optional vector reranking.
 */
@Slf4j
public class LLMDrivenJPAQueryService {
    private static final ObjectMapper METADATA_MAPPER = new ObjectMapper();

    private final RelationshipQueryPlanner planner;
    private final DynamicJPAQueryBuilder queryBuilder;
    private final RelationshipQueryValidator validator;
    private final RelationshipQueryProperties properties;
    private final RelationshipModuleMetadata moduleMetadata;
    private final RelationshipTraversalService jpaTraversalService;
    private final RelationshipQueryDocumentMapper documentMapper;
    private final VectorDatabaseService vectorDatabaseService;
    private final AIEmbeddingService embeddingService;
    private final QueryCache queryCache;
    private final QueryMetrics queryMetrics;

    public LLMDrivenJPAQueryService(RelationshipQueryPlanner planner,
                                    DynamicJPAQueryBuilder queryBuilder,
                                    RelationshipQueryValidator validator,
                                    RelationshipQueryProperties properties,
                                    RelationshipModuleMetadata moduleMetadata,
                                    RelationshipTraversalService jpaTraversalService,
                                    RelationshipQueryDocumentMapper documentMapper,
                                    VectorDatabaseService vectorDatabaseService,
                                      AIEmbeddingService embeddingService,
                                      QueryCache queryCache,
                                      QueryMetrics queryMetrics) {
        this.planner = planner;
        this.queryBuilder = queryBuilder;
        this.validator = validator;
        this.properties = properties;
        this.moduleMetadata = moduleMetadata;
        this.jpaTraversalService = jpaTraversalService;
        this.documentMapper = documentMapper;
        this.vectorDatabaseService = vectorDatabaseService;
        this.embeddingService = embeddingService;
        this.queryCache = queryCache;
        this.queryMetrics = queryMetrics;
    }

    public RAGResponse executeRelationshipQuery(String query, List<String> entityTypes) {
        return executeRelationshipQuery(query, entityTypes, QueryOptions.defaults());
    }

    public RAGResponse executeRelationshipQuery(String query,
                                                List<String> entityTypes,
                                                QueryOptions options) {
        long startNano = System.nanoTime();
        try {
            if (log.isInfoEnabled()) {
                log.info("[RelationshipQuery] Executing query='{}' entityTypes={}", query, entityTypes);
            }
            RelationshipQueryPlan plan = planner.planQuery(query, entityTypes);
            validator.validate(plan);
            if (log.isInfoEnabled()) {
                log.info("[RelationshipQuery] Planner output:\n{}", toJson(plan));
            }

            JpqlQuery jpqlQuery = queryBuilder.buildQuery(plan);
            if (jpqlQuery != null && log.isInfoEnabled()) {
                log.info("[RelationshipQuery] JPQL statement: {}\nParameters: {}", jpqlQuery.getJpql(), jpqlQuery.getParameters());
            }
            ReturnMode returnMode = resolveReturnMode(options);
            TraversalResult traversal = executeTraversal(plan, jpqlQuery, returnMode);
            List<String> entityIds = traversal.entityIds();

            QueryMode mode = resolveMode(plan, options);
            if (mode == QueryMode.ENHANCED) {
                entityIds = rerankWithVectors(plan, entityIds, options);
            }

            List<String> warnings = new LinkedList<>();
            List<RAGResponse.RAGDocument> documents = materializeDocuments(plan, traversal, entityIds, returnMode, options, warnings);
            if (documents.isEmpty()) {
                warnings.add("No results found.");
            }
            long duration = Math.max(0, (System.nanoTime() - startNano) / 1_000_000);

            recordExecutionMetrics(mode, duration, documents.size(), plan.isNeedsSemanticSearch() || mode == QueryMode.ENHANCED);
            updateCacheMetrics();

            RAGResponse response = RAGResponse.builder()
                .originalQuery(query)
                .entityType(plan.getPrimaryEntityType())
                .documents(documents)
                .totalResults(entityIds.size())
                .returnedResults(documents.size())
                .hybridSearchUsed(mode == QueryMode.ENHANCED)
                .success(true)
                .processingTimeMs(duration)
                .confidenceScore(plan.getConfidenceScore())
                .warnings(warnings)
                .metadata(Map.of(
                    "plan", plan,
                    "mode", mode,
                    "timestamp", Instant.now().toString()
                ))
                .build();
            logResultArtifacts(query, entityTypes, plan, jpqlQuery, response);
            return response;
        } catch (RuntimeException ex) {
            long duration = Math.max(0, (System.nanoTime() - startNano) / 1_000_000);
            if (queryMetrics != null && queryMetrics.isEnabled()) {
                queryMetrics.recordExecutionFailure(duration);
            }
            throw ex;
        }
    }

    private TraversalResult executeTraversal(RelationshipQueryPlan plan, JpqlQuery query, ReturnMode returnMode) {
        boolean allowCache = returnMode == ReturnMode.IDS;
        return executeJpaTraversal(plan, query, allowCache);
    }

    /**
     * Resolves the execution mode based on LLM's analysis and configuration.
     * 
     * <p><strong>Simple Decision:</strong></p>
     * <ul>
     *   <li>LLM says semantic search needed + vector search enabled → ENHANCED</li>
     *   <li>Otherwise → STANDALONE</li>
     * </ul>
     * 
     * <p>The LLM analyzes each query to determine if semantic search would improve results.
     * Configuration ({@code enable-vector-search}) acts as a constraint.</p>
     */
    private QueryMode resolveMode(RelationshipQueryPlan plan, QueryOptions options) {
        // LLM analyzed the query and determined semantic search is needed
        if (plan.isNeedsSemanticSearch()) {
            // Configuration check: is vector search enabled?
            if (!moduleMetadata.vectorSearchEnabled()) {
                log.info("LLM recommended semantic search for query: '{}', but enable-vector-search=false in configuration. " +
                    "Using STANDALONE mode.", plan.getOriginalQuery());
                return QueryMode.STANDALONE;
            }
            
            // System availability check: is vector DB present?
            if (!vectorDependenciesPresent()) {
                log.warn("LLM recommended semantic search for query: '{}', but vector database is not available. " +
                    "Using STANDALONE mode. Results may have lower relevance.", plan.getOriginalQuery());
                return QueryMode.STANDALONE;
            }
            
            // All checks passed - use ENHANCED as LLM recommended
            return QueryMode.ENHANCED;
        }
        
        // LLM decided semantic search not needed - use pure relational
        return QueryMode.STANDALONE;
    }

    private boolean vectorDependenciesPresent() {
        return vectorDatabaseService != null && embeddingService != null;
    }

    private ReturnMode resolveReturnMode(QueryOptions options) {
        if (options != null && options.getReturnMode() != null) {
            return options.getReturnMode();
        }
        return properties.getDefaultReturnMode();
    }

    private List<RAGResponse.RAGDocument> materializeDocuments(RelationshipQueryPlan plan,
                                                               TraversalResult traversal,
                                                               List<String> orderedEntityIds,
                                                               ReturnMode returnMode,
                                                               QueryOptions options,
                                                               List<String> warnings) {
        List<String> limited = applyLimit(plan, orderedEntityIds, options);
        if (returnMode == ReturnMode.IDS) {
            return limited.stream()
                .map(id -> RAGResponse.RAGDocument.builder().id(id).build())
                .collect(Collectors.toList());
        }

        if (traversal == null || traversal.entities().isEmpty()) {
            warnings.add("RELATIONAL_MATERIALIZATION_UNAVAILABLE: JPQL did not return entities; returning IDs only.");
            return limited.stream()
                .map(id -> RAGResponse.RAGDocument.builder().id(id).build())
                .collect(Collectors.toList());
        }

        Map<String, Object> entityById = new HashMap<>();
        List<?> entities = traversal.entities();
        List<String> ids = traversal.entityIds();
        for (int i = 0; i < ids.size() && i < entities.size(); i++) {
            entityById.put(ids.get(i), entities.get(i));
        }

        List<RAGResponse.RAGDocument> documents = new ArrayList<>();
        for (String entityId : limited) {
            Object entity = entityById.get(entityId);
            if (entity == null) {
                warnings.add("RELATIONAL_MATERIALIZATION_MISSING_ENTITY: entityId=" + entityId);
                documents.add(RAGResponse.RAGDocument.builder().id(entityId).build());
                continue;
            }
            Optional<RAGResponse.RAGDocument> mapped = documentMapper != null
                ? documentMapper.map(plan.getPrimaryEntityType(), entity, entityId)
                : Optional.empty();
            if (mapped.isPresent()) {
                documents.add(mapped.get());
            } else {
                warnings.add("RELATIONAL_MATERIALIZATION_MISSING_MAPPING: entityType=" + plan.getPrimaryEntityType());
                documents.add(RAGResponse.RAGDocument.builder().id(entityId).build());
            }
        }

        return documents;
    }

    private List<String> applyLimit(RelationshipQueryPlan plan, List<String> entityIds, QueryOptions options) {
        if (entityIds.isEmpty()) {
            return entityIds;
        }
        int limit = Optional.ofNullable(options.getLimit())
            .or(() -> Optional.ofNullable(plan.getLimit()))
            .orElse(50);
        return entityIds.stream().limit(limit).collect(Collectors.toList());
    }

    private List<String> rerankWithVectors(RelationshipQueryPlan plan,
                                           List<String> entityIds,
                                           QueryOptions options) {
        if (!vectorDependenciesPresent() || !StringUtils.hasText(plan.getSemanticQuery())) {
            return entityIds;
        }
        try {
            String embeddingKey = QueryCache.hash(plan.getSemanticQuery());
            AIEmbeddingResponse embedding = null;
            if (queryCache.isEnabled()) {
                embedding = queryCache.getEmbedding(embeddingKey).orElse(null);
            }
            if (embedding == null) {
                embedding = embeddingService.generateEmbedding(
                    AIEmbeddingRequest.builder()
                        .text(plan.getSemanticQuery())
                        .entityType(plan.getPrimaryEntityType())
                        .build()
                );
                if (queryCache.isEnabled()) {
                    queryCache.putEmbedding(embeddingKey, embedding);
                }
            }
            AISearchResponse response = vectorDatabaseService.searchByEntityType(
                embedding.getEmbedding(),
                plan.getPrimaryEntityType(),
                Optional.ofNullable(options.getLimit()).orElse(25),
                Optional.ofNullable(options.getSimilarityThreshold())
                    .orElse(moduleMetadata.similarityThreshold())
            );
            if (response == null || CollectionUtils.isEmpty(response.getResults())) {
                return entityIds;
            }
            List<String> reranked = response.getResults().stream()
                .map(result -> {
                    Object entityId = result.get("entityId");
                    if (entityId == null) {
                        entityId = result.get("id");
                    }
                    return entityId != null ? entityId.toString() : null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
            if (reranked.isEmpty()) {
                return entityIds;
            }
            Set<String> allowed = new LinkedHashSet<>(entityIds);
            List<String> reordered = new ArrayList<>(entityIds.size());
            for (String id : reranked) {
                if (allowed.remove(id)) {
                    reordered.add(id);
                }
            }
            reordered.addAll(allowed);
            return reordered;
        } catch (Exception ex) {
            log.warn("Vector reranking failed: {}", ex.getMessage());
            return entityIds;
        }
    }

    private TraversalResult executeJpaTraversal(RelationshipQueryPlan plan, JpqlQuery query, boolean allowCache) {
        if (query == null || !StringUtils.hasText(query.getJpql())) {
            return TraversalResult.empty();
        }
        String cacheKey = QueryCache.hash(query);
        if (allowCache && queryCache.isEnabled()) {
            Optional<List<String>> cached = queryCache.getQueryResult(cacheKey);
            if (cached.isPresent()) {
                return TraversalResult.ids(cached.get());
            }
        }
        TraversalResult result = jpaTraversalService.traverse(plan, query);
        List<String> entityIds = result.entityIds();
        if (queryCache.isEnabled()) {
            queryCache.putQueryResult(cacheKey, entityIds);
        }
        return result;
    }

    private void recordExecutionMetrics(QueryMode mode, long latencyMs, int resultCount, boolean fallbackUsed) {
        if (queryMetrics != null && queryMetrics.isEnabled()) {
            queryMetrics.recordExecution(mode != null ? mode : QueryMode.STANDALONE, latencyMs, resultCount, fallbackUsed);
        }
    }

    private void updateCacheMetrics() {
        if (queryMetrics != null && queryMetrics.isEnabled()) {
            queryMetrics.updateCacheStats(
                queryCache.getPlanStats(),
                queryCache.getEmbeddingStats(),
                queryCache.getResultStats()
            );
        }
    }

    private void logResultArtifacts(String query,
                                    List<String> entityTypes,
                                    RelationshipQueryPlan plan,
                                    JpqlQuery jpqlQuery,
                                    RAGResponse response) {
        if (!log.isInfoEnabled() || response == null) {
            return;
        }
        log.info("[RelationshipQuery] Result summary -> query='{}', entityTypes={}, totalCandidates={}, returned={}, hybridMode={}",
            query,
            entityTypes,
            response.getTotalResults(),
            response.getReturnedResults(),
            Boolean.TRUE.equals(response.getHybridSearchUsed()));
        log.info("[RelationshipQuery] Document IDs: {}", response.getDocuments() == null ? List.of() : previewDocumentIds(response.getDocuments()));
        if (jpqlQuery != null) {
            log.info("[RelationshipQuery] JPQL (repeated for reference): {}\nParameters: {}", jpqlQuery.getJpql(), jpqlQuery.getParameters());
        }
        log.info("[RelationshipQuery] Serialized response:\n{}", toJson(response));
    }

    private List<String> previewDocumentIds(List<RAGResponse.RAGDocument> documents) {
        if (CollectionUtils.isEmpty(documents)) {
            return List.of();
        }
        return documents.stream()
            .map(RAGResponse.RAGDocument::getId)
            .filter(Objects::nonNull)
            .limit(5)
            .collect(Collectors.toList());
    }

    private String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return METADATA_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception ex) {
            return value.toString();
        }
    }
}
