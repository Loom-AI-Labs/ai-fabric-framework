package com.ai.infrastructure.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO for Advanced RAG responses with enhanced results
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdvancedRAGResponse {
    
    /**
     * Request ID for tracking
     */
    private String requestId;
    
    /**
     * Original query
     */
    private String query;
    
    /**
     * Expanded queries used
     */
    private List<String> expandedQueries;
    
    /**
     * Generated response text
     */
    private String response;
    
    /**
     * Optimized context used for generation
     */
    private String context;
    
    /**
     * Retrieved documents
     */
    private List<RAGDocument> documents;
    
    /**
     * Total number of documents found
     */
    private Integer totalDocuments;
    
    /**
     * Number of documents used for generation
     */
    private Integer usedDocuments;
    
    /**
     * Relevance scores for documents
     */
    private List<Double> relevanceScores;
    
    /**
     * Overall confidence score
     */
    private Double confidenceScore;
    
    /**
     * Processing time in milliseconds
     */
    private Long processingTimeMs;
    
    /**
     * Whether the response was successful
     */
    private Boolean success;
    
    /**
     * Error message if failed
     */
    private String errorMessage;
    
    /**
     * Re-ranking strategy used
     */
    private String rerankingStrategy;
    
    /**
     * Query expansion level used
     */
    private Integer expansionLevel;
    
    /**
     * Context optimization level used
     */
    private String contextOptimizationLevel;
    
    /**
     * Response metadata
     */
    private Map<String, Object> metadata;
    
    /**
     * Response timestamp
     */
    private LocalDateTime timestamp;
    
    /**
     * Response explanation
     */
    private String explanation;
    
    /**
     * Highlighted response with search terms
     */
    private String highlightedResponse;
    
    /**
     * Query suggestions for refinement
     */
    private List<String> querySuggestions;
    
    /**
     * Related topics
     */
    private List<String> relatedTopics;
    
    /**
     * Document summaries
     */
    private List<String> documentSummaries;
    
    /**
     * RAG Document inner class
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RAGDocument {
        private String id;
        private String content;
        private String title;
        private String type;
        private Double score;
        private Double similarity;
        private Map<String, Object> metadata;
        private String source;
        private LocalDateTime createdAt;
        private String author;
        private List<String> tags;
        private String category;
        private Integer wordCount;
        private String language;
    }
}