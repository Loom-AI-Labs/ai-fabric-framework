package ai.fabric.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * RAG Response DTO
 * 
 * Represents the response from a Retrieval-Augmented Generation (RAG) operation.
 * Contains retrieved documents, relevance scores, and metadata information.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RAGResponse {
    
    /**
     * List of retrieved documents with relevance scores
     */
    private List<RAGDocument> documents;
    
    /**
     * Context used for generation
     */
    private String context;
    
    /**
     * Total documents found
     */
    private Integer totalDocuments;
    
    /**
     * Number of documents used
     */
    private Integer usedDocuments;
    
    /**
     * Relevance scores for the documents
     */
    private List<Double> relevanceScores;
    
    /**
     * Whether the response was successful
     */
    private Boolean success;
    
    /**
     * Whether hybrid search was used
     */
    private Boolean hybridSearchUsed;
    
    /**
     * Whether contextual search was used
     */
    private Boolean contextualSearchUsed;
    
    /**
     * Categories that were searched
     */
    private List<String> searchedCategories;
    
    /**
     * Total number of results found
     */
    private Integer totalResults;
    
    /**
     * Number of results returned in this response
     */
    private Integer returnedResults;
    
    /**
     * Maximum relevance score among all results
     */
    private Double maxScore;
    
    /**
     * Average relevance score of returned results
     */
    private Double averageScore;
    
    /**
     * Processing time in milliseconds
     */
    private Long processingTimeMs;
    
    /**
     * Request ID for tracking
     */
    private String requestId;
    
    /**
     * Original query provided to RAG.
     * <p>
     * This value is expected to be pre-processed by the orchestrator
     * (PII redacted, sanitized) before being sent to the RAG module.
     */
    private String originalQuery;
    
    /**
     * Entity type that was queried
     */
    private String entityType;
    
    /**
     * Model used for processing
     */
    private String model;
    
    /**
     * Timestamp when response was generated
     */
    private LocalDateTime timestamp;
    
    /**
     * Additional metadata about the response
     */
    private Map<String, Object> metadata;
    
    /**
     * Whether the response was served from cache
     */
    private Boolean fromCache;

    /**
     * Cache hit rate for this request
     */
    private Double cacheHitRate;
    
    /**
     * Error message if any errors occurred
     */
    private String errorMessage;
    
    /**
     * Warning messages if any warnings occurred
     */
    private List<String> warnings;
    
    /**
     * Suggestions for improving the query
     */
    private List<String> suggestions;
    
    /**
     * Related queries that might be of interest
     */
    private List<String> relatedQueries;
    
    /**
     * Facets for filtering results
     */
    private Map<String, List<String>> facets;
    
    /**
     * Aggregations for analytics
     */
    private Map<String, Object> aggregations;
    
    /**
     * Confidence score for the overall response quality
     */
    private Double confidenceScore;
    
    /**
     * Whether the response quality meets the threshold
     */
    private Boolean qualityThresholdMet;
    
    /**
     * RAG Document inner class
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RAGDocument {
        
        /**
         * Document ID
         */
        private String id;
        
        /**
         * Document content
         */
        private String content;
        
        /**
         * Document title
         */
        private String title;
        
        /**
         * Document type
         */
        private String type;
        
        /**
         * Relevance score (0.0 to 1.0)
         */
        private Double score;
        
        /**
         * Similarity score
         */
        private Double similarity;
        
        /**
         * Document metadata
         */
        private Map<String, Object> metadata;
        
        /**
         * Document embeddings (if requested)
         */
        private List<Double> embeddings;
        
        /**
         * Highlighted content with search terms
         */
        private String highlightedContent;
        
        /**
         * Document source
         */
        private String source;
        
        /**
         * Document URL or reference
         */
        private String url;
        
        /**
         * Document creation date
         */
        private LocalDateTime createdAt;
        
        /**
         * Document last modified date
         */
        private LocalDateTime modifiedAt;
        
        /**
         * Document author
         */
        private String author;
        
        /**
         * Document tags
         */
        private List<String> tags;
        
        /**
         * Document language
         */
        private String language;
        
        /**
         * Document size in characters
         */
        private Integer size;
        
        /**
         * Document word count
         */
        private Integer wordCount;
        
        /**
         * Document reading time in minutes
         */
        private Double readingTimeMinutes;
        
        /**
         * Document quality score
         */
        private Double qualityScore;
        
        /**
         * Document freshness score
         */
        private Double freshnessScore;
        
        /**
         * Document authority score
         */
        private Double authorityScore;
    }
    
    // Manual getters/setters for Lombok compatibility
    public String getQuery() {
        return originalQuery;
    }
    
    public void setQuery(String query) {
        this.originalQuery = query;
    }
}