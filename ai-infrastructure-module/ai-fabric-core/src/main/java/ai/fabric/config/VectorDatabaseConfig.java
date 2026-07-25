package ai.fabric.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for vector database backends
 * 
 * This class contains configuration properties for different vector database
 * implementations including Lucene, Pinecone, and in-memory stores.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.vector-db")
public class VectorDatabaseConfig {
    
    /**
     * Vector database type: lucene, pinecone, memory
     */
    private String type = "lucene";
    
    /**
     * Lucene-specific configuration
     */
    private LuceneConfig lucene = new LuceneConfig();
    
    /**
     * Pinecone-specific configuration
     */
    private PineconeConfig pinecone = new PineconeConfig();
    
    /**
     * Memory-specific configuration
     */
    private MemoryConfig memory = new MemoryConfig();

    /**
     * Operational options for vector lifecycle operations.
     */
    private OperationsConfig operations = new OperationsConfig();

    @Data
    public static class OperationsConfig {
        /**
         * When clearing vectors from remote backends, optionally wait for eventual consistency
         * (i.e., poll until counts reach zero) to avoid delete/write races in tests and automation.
         */
        private Boolean awaitClearConsistency = true;

        /**
         * Maximum time (ms) to wait for clear operations to become visible.
         */
        private Long awaitClearTimeoutMs = 30_000L;

        /**
         * When true, Qdrant metadata-filtered operations fail closed if a required payload index is
         * missing. When false, AI Fabric repairs portable typed payload indexes and retries the same
         * provider-filtered operation once; it never retries without the metadata filter.
         */
        private Boolean failOnMissingPayloadIndex = false;
    }
    
    @Data
    public static class LuceneConfig {
        /**
         * Path to Lucene index directory
         */
        private String indexPath = "./data/lucene-vector-index";
        
        /**
         * Similarity threshold for search results
         */
        private Double similarityThreshold = 0.7;
        
        /**
         * Maximum number of results to return
         */
        private Integer maxResults = 100;
        
        /**
         * Whether to create index directory if it doesn't exist
         */
        private Boolean createIndexIfNotExists = true;
        
        /**
         * Lucene analyzer to use
         */
        private String analyzer = "standard";
        
        /**
         * Whether to enable compound file format
         */
        private Boolean useCompoundFile = true;
        
        /**
         * Maximum number of documents to buffer in memory
         */
        private Integer maxBufferedDocs = 1000;
    }
    
    @Data
    public static class PineconeConfig {
        /**
         * Pinecone API key
         */
        private String apiKey;
        
        /**
         * Pinecone environment
         */
        private String environment = "us-east-1-aws";
        
        /**
         * Pinecone index name
         */
        private String indexName = "ai-infrastructure";
        
        /**
         * Vector dimensions
         */
        private Integer dimensions = 1536;
        
        /**
         * Similarity metric: cosine, dotproduct, euclidean
         */
        private String metric = "cosine";
        
        /**
         * Number of pods for the index
         */
        private Integer pods = 1;
        
        /**
         * Pod type: p1, p2, s1, s2
         */
        private String podType = "p1";
        
        /**
         * Whether to enable metadata filtering
         */
        private Boolean enableMetadataFiltering = true;
    }
    
    @Data
    public static class MemoryConfig {
        /**
         * Whether to enable persistence to disk
         */
        private Boolean enablePersistence = false;
        
        /**
         * Path to persistence file
         */
        private String persistencePath = "./data/memory-vector-store.json";
        
        /**
         * Maximum number of vectors to store in memory
         */
        private Integer maxVectors = 10000;
        
        /**
         * Whether to enable automatic cleanup of old vectors
         */
        private Boolean enableCleanup = true;
        
        /**
         * Cleanup interval in minutes
         */
        private Integer cleanupIntervalMinutes = 60;
        
        /**
         * Maximum age of vectors in minutes before cleanup
         */
        private Integer maxVectorAgeMinutes = 1440; // 24 hours

        /**
         * Explicit override that allows the in-memory vector provider to start when a production
         * Spring profile is active. This should remain false for real deployments.
         */
        private Boolean allowInProduction = false;
    }
}
