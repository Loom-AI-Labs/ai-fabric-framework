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
     * <p>When null, the active Advanced RAG provider applies its configured default.</p>
     */
    private Integer maxResults;
    
    /**
     * Maximum number of documents to use for context
     * <p>When null, the active Advanced RAG provider applies its configured default.</p>
     */
    private Integer maxDocuments;
    
    /**
     * Query expansion level (1-5)
     * <p>When null, the active Advanced RAG provider applies its configured default.</p>
     */
    private Integer expansionLevel;
    
    /**
     * Re-ranking strategy: semantic, hybrid, diversity, score
     * <p>When blank or null, the active Advanced RAG provider applies its configured default.</p>
     */
    private String rerankingStrategy;
    
    /**
     * Context optimization level: high, medium, low
     * <p>When blank or null, the active Advanced RAG provider applies its configured default.</p>
     */
    private String contextOptimizationLevel;
    
    /**
     * Enable hybrid search
     * <p>When null, the active Advanced RAG provider applies its configured default.</p>
     */
    private Boolean enableHybridSearch;
    
    /**
     * Enable contextual search
     * <p>When null, the active Advanced RAG provider applies its configured default.</p>
     */
    private Boolean enableContextualSearch;
    
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
