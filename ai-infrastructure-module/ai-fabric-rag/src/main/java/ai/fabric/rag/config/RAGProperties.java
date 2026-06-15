package ai.fabric.rag.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for RAG (Retrieval-Augmented Generation) module.
 * 
 * <p>These properties control the behavior of RAG services including:</p>
 * <ul>
 *   <li>Module enablement</li>
 *   <li>Search defaults (limit, threshold)</li>
 *   <li>Advanced features (query expansion, re-ranking)</li>
 *   <li>Performance tuning</li>
 * </ul>
 * 
 * <p><strong>Configuration Example:</strong></p>
 * <pre>{@code
 * ai.infrastructure.rag:
 *   enabled: true
 *   default-limit: 10
 *   default-threshold: 0.7
 *   enable-hybrid-search: false
 *   enable-contextual-search: false
 *   advanced:
 *     enabled: true
 *     default-expansion-level: 3
 *     default-reranking-strategy: semantic
 * }</pre>
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 * @since 1.0
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai.infrastructure.rag")
public class RAGProperties {
    
    // =========================================================================
    // Core Settings
    // =========================================================================
    
    /**
     * Enable RAG module.
     * When disabled, RAG services will not be registered.
     * Default: true
     */
    private boolean enabled = true;
    
    /**
     * Default limit for search results when not specified in request.
     * Default: 10
     */
    @Min(1)
    @Max(1000)
    private int defaultLimit = 10;
    
    /**
     * Default similarity threshold for search results.
     * Results with similarity below this threshold are filtered out.
     * Default: 0.7
     */
    @Min(0)
    @Max(1)
    private double defaultThreshold = 0.7;
    
    /**
     * Enable hybrid search by default (combines vector + keyword search).
     * Default: false
     */
    private boolean enableHybridSearch = false;
    
    /**
     * Enable contextual search by default (considers context in search).
     * Default: false
     */
    private boolean enableContextualSearch = false;
    
    // =========================================================================
    // Indexing Settings
    // =========================================================================
    
    /**
     * Indexing configuration.
     */
    private IndexingProperties indexing = new IndexingProperties();
    
    /**
     * Configuration for content indexing operations.
     */
    @Data
    public static class IndexingProperties {
        
        /**
         * Enable automatic indexing of content.
         * Default: true
         */
        private boolean autoIndex = true;
        
        /**
         * Maximum content length for indexing (in characters).
         * Content exceeding this limit will be truncated.
         * Default: 10000
         */
        @Positive
        private int maxContentLength = 10000;
        
        /**
         * Batch size for bulk indexing operations.
         * Default: 100
         */
        @Min(1)
        @Max(10000)
        private int batchSize = 100;
    }
    
    // =========================================================================
    // Advanced RAG Settings
    // =========================================================================
    
    /**
     * Advanced RAG configuration.
     */
    private AdvancedProperties advanced = new AdvancedProperties();
    
    /**
     * Configuration for advanced RAG features.
     */
    @Data
    public static class AdvancedProperties {
        
        /**
         * Enable advanced RAG service.
         * When disabled, only basic RAG operations are available.
         * Default: true
         */
        private boolean enabled = true;
        
        /**
         * Default query expansion level.
         * Number of additional queries to generate.
         * Default: 3
         */
        @Min(0)
        @Max(10)
        private int defaultExpansionLevel = 3;
        
        /**
         * Default re-ranking strategy.
         * Options: semantic, hybrid, diversity, score
         * Default: semantic
         */
        private String defaultRerankingStrategy = "semantic";
        
        /**
         * Default context optimization level.
         * Options: high, medium, low
         * Default: medium
         */
        private String defaultContextOptimizationLevel = "medium";
        
        /**
         * Maximum documents to use for context.
         * Default: 10
         */
        @Min(1)
        @Max(100)
        private int maxDocuments = 10;
        
        /**
         * Maximum results per expanded query.
         * Default: 20
         */
        @Min(1)
        @Max(100)
        private int maxResultsPerQuery = 20;
    }
    
    // =========================================================================
    // Caching Settings
    // =========================================================================
    
    /**
     * Caching configuration.
     */
    private CachingProperties caching = new CachingProperties();
    
    /**
     * Configuration for RAG caching.
     */
    @Data
    public static class CachingProperties {
        
        /**
         * Enable caching of embeddings.
         * Default: true
         */
        private boolean enableEmbeddingCache = true;
        
        /**
         * Enable caching of search results.
         * Default: false
         */
        private boolean enableSearchCache = false;
        
        /**
         * TTL for cached embeddings in seconds.
         * Default: 3600 (1 hour)
         */
        @Positive
        private int embeddingCacheTtlSeconds = 3600;
        
        /**
         * TTL for cached search results in seconds.
         * Default: 300 (5 minutes)
         */
        @Positive
        private int searchCacheTtlSeconds = 300;
        
        /**
         * Maximum size of embedding cache.
         * Default: 10000
         */
        @Positive
        private int maxEmbeddingCacheSize = 10000;
    }
}
