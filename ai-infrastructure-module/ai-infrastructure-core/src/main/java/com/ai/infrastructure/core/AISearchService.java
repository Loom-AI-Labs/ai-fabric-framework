package com.ai.infrastructure.core;

import com.ai.infrastructure.dto.AISearchRequest;
import com.ai.infrastructure.dto.AISearchResponse;
import com.ai.infrastructure.config.AIProviderConfig;
import com.ai.infrastructure.exception.AIServiceException;
import com.ai.infrastructure.search.VectorSearchService;
import com.ai.infrastructure.service.VectorManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Service for AI semantic search
 * 
 * This service provides semantic search capabilities using vector similarity.
 * It can search across different entity types and provides ranking and filtering.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Slf4j
// @Service // Removed - already defined as @Bean in AIInfrastructureAutoConfiguration
public class AISearchService {
    
    private final AIProviderConfig config;
    private final VectorSearchService vectorSearchService;
    private final VectorManagementService vectorManagementService;

    public AISearchService(AIProviderConfig config,
                           VectorSearchService vectorSearchService,
                           VectorManagementService vectorManagementService) {
        this.config = Objects.requireNonNull(config, "AIProviderConfig must not be null");
        this.vectorSearchService = Objects.requireNonNull(vectorSearchService, "VectorSearchService must not be null");
        this.vectorManagementService = Objects.requireNonNull(vectorManagementService, "VectorManagementService must not be null");
    }
    
    /**
     * Perform semantic search using vector similarity
     * 
     * @param queryVector the query vector for search
     * @param request the search request
     * @return search results with relevance scores
     */
    public AISearchResponse search(List<Double> queryVector, AISearchRequest request) {
        try {
            log.debug("Performing semantic search for query: {}", request.getQuery());
            
            // Use the advanced VectorSearchService for better performance
            return vectorSearchService.search(queryVector, request);
                
        } catch (Exception e) {
            log.error("Error performing semantic search", e);
            throw new AIServiceException("Failed to perform semantic search", e);
        }
    }
    
    /**
     * Perform hybrid search combining vector and text similarity
     * 
     * @param queryVector the query vector for search
     * @param queryText the original query text
     * @param request the search request
     * @return hybrid search results
     */
    public AISearchResponse hybridSearch(List<Double> queryVector, String queryText, AISearchRequest request) {
        try {
            log.debug("Performing hybrid search for query: {}", queryText);
            
            return vectorSearchService.hybridSearch(queryVector, queryText, request);
                
        } catch (Exception e) {
            log.error("Error performing hybrid search", e);
            throw new AIServiceException("Failed to perform hybrid search", e);
        }
    }
    
    /**
     * Perform contextual search with additional context
     * 
     * @param queryVector the query vector for search
     * @param context the search context
     * @param request the search request
     * @return context-aware search results
     */
    public AISearchResponse contextualSearch(List<Double> queryVector, String context, AISearchRequest request) {
        try {
            log.debug("Performing contextual search with context: {}", context);
            
            return vectorSearchService.contextualSearch(queryVector, context, request);
                
        } catch (Exception e) {
            log.error("Error performing contextual search", e);
            throw new AIServiceException("Failed to perform contextual search", e);
        }
    }
    
    /**
     * Index an entity with its embedding
     * 
     * @param entityType the type of entity
     * @param entityId the unique identifier
     * @param content the text content
     * @param embedding the vector embedding
     * @param metadata additional metadata
     */
    public void indexEntity(String entityType, String entityId, String content, 
                           List<Double> embedding, Map<String, Object> metadata) {
        try {
            log.debug("Indexing entity {} of type {}", entityId, entityType);
            
            // Use VectorSearchService for advanced indexing
            vectorSearchService.storeVector(entityType, entityId, content, embedding, metadata);
            log.debug("Successfully indexed entity {} of type {}", entityId, entityType);
            
        } catch (Exception e) {
            log.error("Error indexing entity", e);
            throw new AIServiceException("Failed to index entity", e);
        }
    }
    
    /**
     * Remove an entity from the index
     * 
     * @param entityType the type of entity
     * @param entityId the unique identifier
     */
    public void removeEntity(String entityType, String entityId) {
        try {
            log.debug("Removing entity {} of type {}", entityId, entityType);
            vectorManagementService.removeVector(entityType, entityId);
            log.debug("Successfully removed entity {} of type {}", entityId, entityType);
            
        } catch (Exception e) {
            log.error("Error removing entity", e);
            throw new AIServiceException("Failed to remove entity", e);
        }
    }
    
    /**
     * Get statistics about the backing vector store and search layer.
     *
     * @return map of statistics
     */
    public Map<String, Object> getStatistics() {
        return Map.of(
            "vectorDatabase", vectorManagementService.getStatistics(),
            "searchMetrics", vectorSearchService.getSearchStatistics()
        );
    }
    
    /**
     * Clear all entities from the index
     */
    public void clearIndex() {
        log.debug("Clearing vector store index");
        vectorManagementService.clearAllVectors();
    }
    
    /**
     * Get search statistics and performance metrics
     * 
     * @return map of search statistics
     */
    public Map<String, Object> getSearchStatistics() {
        return vectorSearchService.getSearchStatistics();
    }
}
