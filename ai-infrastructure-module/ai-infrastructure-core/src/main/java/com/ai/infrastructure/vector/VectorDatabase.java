package com.ai.infrastructure.vector;

import com.ai.infrastructure.dto.AISearchRequest;
import com.ai.infrastructure.dto.AISearchResponse;

import java.util.List;
import java.util.Map;

/**
 * Vector database abstraction interface
 * 
 * This interface provides a provider-agnostic way to interact with
 * vector databases for storing and searching embeddings.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
public interface VectorDatabase {
    
    /**
     * Store a vector in the database
     * 
     * @param entityType the type of entity
     * @param entityId the unique identifier
     * @param content the text content
     * @param embedding the vector embedding
     * @param metadata additional metadata
     */
    void storeVector(String entityType, String entityId, String content, 
                    List<Double> embedding, Map<String, Object> metadata);
    
    /**
     * Search for similar vectors
     * 
     * @param queryVector the query vector
     * @param request the search request
     * @return search results
     */
    AISearchResponse search(List<Double> queryVector, AISearchRequest request);
    
    /**
     * Remove a vector from the database
     * 
     * @param entityType the type of entity
     * @param entityId the unique identifier
     */
    void removeVector(String entityType, String entityId);
    
    /**
     * Update a vector in the database
     * 
     * @param entityType the type of entity
     * @param entityId the unique identifier
     * @param content the text content
     * @param embedding the vector embedding
     * @param metadata additional metadata
     */
    void updateVector(String entityType, String entityId, String content, 
                     List<Double> embedding, Map<String, Object> metadata);
    
    /**
     * Get vector by ID
     * 
     * @param entityType the type of entity
     * @param entityId the unique identifier
     * @return vector data
     */
    Map<String, Object> getVector(String entityType, String entityId);
    
    /**
     * Check if vector exists
     * 
     * @param entityType the type of entity
     * @param entityId the unique identifier
     * @return true if vector exists
     */
    boolean vectorExists(String entityType, String entityId);
    
    /**
     * Get all vectors for an entity type
     * 
     * @param entityType the type of entity
     * @return list of vectors
     */
    List<Map<String, Object>> getAllVectors(String entityType);
    
    /**
     * Clear all vectors for an entity type
     * 
     * @param entityType the type of entity
     */
    void clearVectors(String entityType);
    
    /**
     * Clear all vectors
     */
    void clearAllVectors();
    
    /**
     * Get database statistics
     * 
     * @return database statistics
     */
    Map<String, Object> getStatistics();
    
    /**
     * Check if database is healthy
     * 
     * @return true if database is healthy
     */
    boolean isHealthy();
    
    /**
     * Get database information
     * 
     * @return database information
     */
    Map<String, Object> getInfo();
    
    /**
     * Batch store vectors
     * 
     * @param vectors list of vectors to store
     */
    void batchStoreVectors(List<VectorData> vectors);
    
    /**
     * Batch search vectors
     * 
     * @param queries list of search queries
     * @return list of search results
     */
    List<AISearchResponse> batchSearch(List<VectorSearchQuery> queries);
    
    /**
     * Vector data class
     */
    class VectorData {
        private String entityType;
        private String entityId;
        private String content;
        private List<Double> embedding;
        private Map<String, Object> metadata;
        
        public VectorData(String entityType, String entityId, String content, 
                         List<Double> embedding, Map<String, Object> metadata) {
            this.entityType = entityType;
            this.entityId = entityId;
            this.content = content;
            this.embedding = embedding;
            this.metadata = metadata;
        }
        
        // Getters and setters
        public String getEntityType() { return entityType; }
        public void setEntityType(String entityType) { this.entityType = entityType; }
        
        public String getEntityId() { return entityId; }
        public void setEntityId(String entityId) { this.entityId = entityId; }
        
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        
        public List<Double> getEmbedding() { return embedding; }
        public void setEmbedding(List<Double> embedding) { this.embedding = embedding; }
        
        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    }
    
    /**
     * Vector search query class
     */
    class VectorSearchQuery {
        private List<Double> queryVector;
        private AISearchRequest request;
        
        public VectorSearchQuery(List<Double> queryVector, AISearchRequest request) {
            this.queryVector = queryVector;
            this.request = request;
        }
        
        // Getters and setters
        public List<Double> getQueryVector() { return queryVector; }
        public void setQueryVector(List<Double> queryVector) { this.queryVector = queryVector; }
        
        public AISearchRequest getRequest() { return request; }
        public void setRequest(AISearchRequest request) { this.request = request; }
    }
}
