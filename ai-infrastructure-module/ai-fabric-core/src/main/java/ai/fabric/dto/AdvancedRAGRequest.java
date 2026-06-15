package ai.fabric.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * DTO for Advanced RAG requests with query expansion and re-ranking
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdvancedRAGRequest {
    
    /**
     * Original query
     */
    private String query;
    
    /**
     * Maximum number of results to return
     */
    @Builder.Default
    private Integer maxResults = 10;
    
    /**
     * Maximum number of documents to use for context
     */
    @Builder.Default
    private Integer maxDocuments = 5;
    
    /**
     * Query expansion level (1-5)
     */
    @Builder.Default
    private Integer expansionLevel = 2;
    
    /**
     * Re-ranking strategy: semantic, hybrid, diversity, score
     */
    @Builder.Default
    private String rerankingStrategy = "hybrid";
    
    /**
     * Context optimization level: high, medium, low
     */
    @Builder.Default
    private String contextOptimizationLevel = "medium";
    
    /**
     * Enable hybrid search
     */
    @Builder.Default
    private Boolean enableHybridSearch = true;
    
    /**
     * Enable contextual search
     */
    @Builder.Default
    private Boolean enableContextualSearch = true;
    
    /**
     * Categories to search in
     */
    private List<String> categories;
    
    /**
     * Specific entity type to target in the vector index
     */
    private String entityType;

    /**
     * Additional context for the query
     */
    private String context;
    
    /**
     * Canonical caller identity for retrieval authorization and traceability.
     */
    private AIAccessSubjectContext authContext;
    
    /**
     * Request metadata
     */
    private Map<String, Object> metadata;
    
    /**
     * Language preference
     */
    private String language;
    
    /**
     * Domain-specific context
     */
    private String domain;
    
    /**
     * Time range for search
     */
    private String timeRange;
    
    /**
     * Minimum confidence score
     */
    @Builder.Default
    private Double minConfidenceScore = 0.5;
    
    /**
     * Enable result explanation
     */
    @Builder.Default
    private Boolean enableExplanation = true;
    
    /**
     * Enable result highlighting
     */
    @Builder.Default
    private Boolean enableHighlighting = true;
    
    /**
     * Custom filters
     */
    private Map<String, Object> filters;

    /**
     * Override for similarity threshold when delegating to RAG service
     */
    private Double similarityThreshold;
    
    /**
     * Request timeout in milliseconds
     */
    @Builder.Default
    private Long timeoutMs = 30000L;
}
