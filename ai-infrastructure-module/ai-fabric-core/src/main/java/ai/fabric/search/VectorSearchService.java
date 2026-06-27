package ai.fabric.search;

import ai.fabric.cache.AICacheNames;
import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.exception.AIServiceException;
import ai.fabric.rag.VectorDatabaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.lang.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Advanced vector search service - delegates to VectorDatabaseService
 * 
 * This service provides a facade layer over VectorDatabaseService implementations.
 * The actual similarity calculations are handled by the VectorDatabaseService
 * implementation (Lucene with native k-NN, Pinecone, Qdrant, etc.), making the
 * system swappable between different vector database backends.
 * 
 * All implementations handle similarity internally, so manual calculations
 * are no longer needed.
 * 
 * @author AI Infrastructure Team
 * @version 2.0.0
 */
@Slf4j
public class VectorSearchService {

    private static final String VECTOR_SEARCH_CACHE = AICacheNames.VECTOR_SEARCH;
    
    private final AIProviderConfig config;
    private final VectorDatabaseService vectorDatabaseService;
    private final CacheManager cacheManager;
    
    // Performance metrics
    private final AtomicLong totalSearches = new AtomicLong(0);
    private final AtomicLong totalSearchTime = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);

    public VectorSearchService(AIProviderConfig config,
                               VectorDatabaseService vectorDatabaseService,
                               @Nullable CacheManager cacheManager) {
        this.config = config;
        this.vectorDatabaseService = vectorDatabaseService;
        this.cacheManager = cacheManager != null ? cacheManager : new NoOpCacheManager();
    }
    
    /**
     * Perform vector search - delegates to VectorDatabaseService
     * 
     * The VectorDatabaseService implementation (Lucene, Pinecone, Qdrant, etc.)
     * handles similarity calculations internally using optimized algorithms.
     * 
     * @param queryVector the query vector
     * @param request the search request
     * @return search results with similarity scores calculated by the vector database
     */
    public AISearchResponse search(List<Double> queryVector, AISearchRequest request) {
        totalSearches.incrementAndGet();

        String cacheKey = buildCacheKey(queryVector, request);
        Cache cache = getSearchCache();

        AISearchResponse cachedResponse = getFromCache(cache, cacheKey);
        if (cachedResponse != null) {
            cacheHits.incrementAndGet();
            log.debug("Vector search cache hit for query: {}", request.getQuery());
            return cachedResponse;
        }

        long startTime = System.currentTimeMillis();
        AISearchResponse response = executeSearch(queryVector, request);
        long processingTime = System.currentTimeMillis() - startTime;

        totalSearchTime.addAndGet(processingTime);
        cacheMisses.incrementAndGet();

        if (cache != null) {
            cache.put(cacheKey, response);
        }

        log.debug("Vector database returned {} results in {}ms", response.getTotalResults(), processingTime);
        return response;
    }
    
    /**
     * Perform hybrid search combining vector similarity and text matching
     * 
     * Note: Hybrid search combines vector search (handled by VectorDatabaseService)
     * with text matching. Vector similarity is handled by the vector database.
     * 
     * @param queryVector the query vector
     * @param queryText the original query text
     * @param request the search request
     * @return hybrid search results
     */
    public AISearchResponse hybridSearch(List<Double> queryVector, String queryText, AISearchRequest request) {
        try {
            log.debug("Performing hybrid search for query: {}", queryText);

            return vectorDatabaseService.hybridSearch(queryVector, queryText, request);
                
        } catch (Exception e) {
            log.error("Error performing hybrid search", e);
            throw new AIServiceException("Failed to perform hybrid search", e);
        }
    }
    
    /**
     * Perform semantic search with context awareness
     * 
     * Delegates to VectorDatabaseService which handles similarity internally.
     * Context can be added to request metadata for filtering.
     * 
     * @param queryVector the query vector
     * @param context the search context
     * @param request the search request
     * @return context-aware search results
     */
    public AISearchResponse contextualSearch(List<Double> queryVector, String context, AISearchRequest request) {
        try {
            log.debug("Performing contextual search with context: {}", context);
            
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

            // Delegate to VectorDatabaseService - it handles similarity internally
            return vectorDatabaseService.search(queryVector, contextualRequest);
                
        } catch (Exception e) {
            log.error("Error performing contextual search", e);
            throw new AIServiceException("Failed to perform contextual search", e);
        }
    }
    
    /**
     * Store vector with metadata - delegates to VectorDatabaseService
     * 
     * @param entityType the type of entity
     * @param entityId the unique identifier
     * @param content the text content
     * @param embedding the vector embedding
     * @param metadata additional metadata
     */
    public void storeVector(String entityType, String entityId, String content, 
                           List<Double> embedding, Map<String, Object> metadata) {
        try {
            log.debug("Storing vector via VectorDatabaseService for entity {} of type {}", entityId, entityType);
            
            // Delegate to VectorDatabaseService - it handles storage and indexing
            vectorDatabaseService.storeVector(entityType, entityId, content, embedding, metadata);
            evictSearchCache();
            
            log.debug("Successfully stored vector for entity {} of type {}", entityId, entityType);
            
        } catch (Exception e) {
            log.error("Error storing vector", e);
            throw new AIServiceException("Failed to store vector", e);
        }
    }
    
    /**
     * Get search statistics and performance metrics
     * 
     * @return map of search statistics
     */
    public Map<String, Object> getSearchStatistics() {
        long totalSearchesCount = totalSearches.get();
        long totalSearchTimeMs = totalSearchTime.get();
        double avgSearchTime = totalSearchesCount > 0 ? (double) totalSearchTimeMs / totalSearchesCount : 0.0;
        long cacheHitsCount = cacheHits.get();
        long cacheMissesCount = cacheMisses.get();
        long cacheRequests = cacheHitsCount + cacheMissesCount;
        double cacheHitRate = cacheRequests > 0 ? (double) cacheHitsCount / cacheRequests : 0.0;
        
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalSearches", totalSearchesCount);
        stats.put("totalSearchTimeMs", totalSearchTimeMs);
        stats.put("averageSearchTimeMs", avgSearchTime);
        stats.put("cacheHits", cacheHitsCount);
        stats.put("cacheMisses", cacheMissesCount);
        stats.put("cacheHitRate", cacheHitRate);
        stats.put("vectorDatabase", vectorDatabaseService.getStatistics());
        return stats;
    }
    
    /**
     * Note: Similarity calculations are now handled by VectorDatabaseService implementations.
     * 
     * - LuceneVectorDatabaseService: Uses native k-NN (KnnVectorQuery) with HNSW indexing
     * - PineconeVectorDatabaseService: Uses Pinecone's optimized similarity search
     * - QdrantVectorDatabaseService: Uses Qdrant's HNSW implementation
     * 
     * This abstraction allows swapping between vector databases without changing application code.
     * All implementations handle similarity internally, providing optimized performance.
     */

    public void clearMetrics() {
        totalSearches.set(0);
        totalSearchTime.set(0);
        cacheHits.set(0);
        cacheMisses.set(0);
    }

    private AISearchResponse executeSearch(List<Double> queryVector, AISearchRequest request) {
        try {
            return vectorDatabaseService.search(queryVector, request);
        } catch (Exception e) {
            log.error("Error performing vector search", e);
            throw new AIServiceException("Failed to perform vector search", e);
        }
    }

    private Cache getSearchCache() {
        return cacheManager.getCache(VECTOR_SEARCH_CACHE);
    }

    private AISearchResponse getFromCache(Cache cache, String cacheKey) {
        return cache != null ? cache.get(cacheKey, AISearchResponse.class) : null;
    }

    private void evictSearchCache() {
        try {
            Cache cache = getSearchCache();
            if (cache != null) {
                cache.clear();
                log.debug("Evicted {} cache after vector index mutation", VECTOR_SEARCH_CACHE);
            }
        } catch (RuntimeException ex) {
            log.warn("Unable to evict {} cache after vector index mutation: {}", VECTOR_SEARCH_CACHE, ex.getMessage());
            log.debug("Vector search cache eviction failure", ex);
        }
    }

    private String buildCacheKey(List<Double> queryVector, AISearchRequest request) {
        int vectorHash = queryVector != null ? queryVector.hashCode() : 0;
        int requestHash = request != null ? request.hashCode() : 0;
        return vectorHash + "_" + requestHash;
    }
}
