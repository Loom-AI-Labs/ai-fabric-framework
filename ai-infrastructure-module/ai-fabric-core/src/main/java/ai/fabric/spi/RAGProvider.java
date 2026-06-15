package ai.fabric.spi;

import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;

import java.util.Map;

/**
 * Service Provider Interface for RAG (Retrieval-Augmented Generation) operations.
 * 
 * <p>This interface defines the contract for RAG providers that can be used by the
 * orchestration pipeline. The core module depends on this interface; the RAG module
 * (or custom implementations) provide the actual implementation.</p>
 * 
 * <p><strong>Usage Pattern:</strong></p>
 * <ul>
 *   <li>Pipeline steps ({@code IntentHandlingStep}, {@code SmartSuggestionsStep}) inject this interface</li>
 *   <li>The default implementation ({@code RAGService}) is provided by the {@code ai-infrastructure-rag} module</li>
 *   <li>Applications can provide custom implementations for specialized RAG behavior</li>
 * </ul>
 * 
 * <p><strong>Implementation Requirements:</strong></p>
 * <ul>
 *   <li>Implementations MUST be thread-safe</li>
 *   <li>Implementations assume queries are pre-processed (PII handled by orchestrator)</li>
 *   <li>Implementations MUST return non-null {@link RAGResponse} objects</li>
 *   <li>On error, return a RAGResponse with {@code success=false} rather than throwing exceptions</li>
 * </ul>
 * 
 * <p><strong>Example Implementation:</strong></p>
 * <pre>{@code
 * @Service
 * public class RAGService implements RAGProvider {
 *     
 *     @Override
 *     public RAGResponse performRag(RAGRequest request) {
 *         // Retrieval-focused implementation
 *     }
 *     
 *     @Override
 *     public RAGResponse performRAGQuery(RAGRequest request) {
 *         // Retrieval + context-building implementation (generation is handled by orchestrator)
 *     }
 * }
 * }</pre>
 * 
 * <p><strong>Module Dependency:</strong></p>
 * <pre>{@code
 * <!-- Include RAG module for default implementation -->
 * <dependency>
 *     <groupId>com.ai.fabric</groupId>
 *     <artifactId>ai-infrastructure-rag</artifactId>
 * </dependency>
 * }</pre>
 * 
 * @see RAGRequest
 * @see RAGResponse
 * @since 1.0
 * @author AI Infrastructure Team
 */
public interface RAGProvider {
    
    // =========================================================================
    // Constants
    // =========================================================================
    
    /**
     * Default limit for RAG queries when not specified.
     */
    int DEFAULT_LIMIT = 10;
    
    /**
     * Default similarity threshold for RAG queries.
     */
    double DEFAULT_THRESHOLD = 0.7;
    
    // =========================================================================
    // Core RAG Operations
    // =========================================================================
    
    /**
     * Perform RAG operation with retrieval focus.
     * 
     * <p>This method performs semantic search and returns matching documents
     * without generating new content. Use this for information retrieval
     * use cases where the raw documents are sufficient.</p>
     * 
     * <p><strong>Process:</strong></p>
     * <ol>
     *   <li>Generate embedding for the query</li>
     *   <li>Perform vector similarity search</li>
     *   <li>Return matching documents with relevance scores</li>
     * </ol>
     * 
     * @param request the RAG request containing query, entity type, and options
     * @return RAGResponse with retrieved documents and metadata; never null
     * @throws IllegalArgumentException if request is null or query is blank
     * @see #performRAGQuery(RAGRequest) for generation-focused operations
     */
    RAGResponse performRag(RAGRequest request);
    
    /**
     * Perform RAG query with context-building support.
     * 
     * <p>This method performs semantic search and optionally generates
 * a context string for downstream generation. Generation is handled by the
 * orchestrator (pipeline) or a higher-level service, not by the RAG provider.</p>
     * 
     * <p><strong>Process:</strong></p>
     * <ol>
     *   <li>Generate embedding for the query</li>
     *   <li>Perform vector similarity search (with hybrid/contextual options)</li>
     *   <li>Build context from retrieved documents</li>
 *   <li>Return documents + context + metadata</li>
     * </ol>
     * 
     * <p><strong>Search Modes:</strong></p>
     * <ul>
     *   <li>Standard: Pure vector similarity search</li>
     *   <li>Hybrid: Combined vector + keyword search (when {@code enableHybridSearch=true})</li>
     *   <li>Contextual: Context-aware search (when {@code enableContextualSearch=true})</li>
     * </ul>
     * 
     * @param request the RAG request containing query, entity type, and options
 * @return RAGResponse with context and documents; never null
     * @throws IllegalArgumentException if request is null or query is blank
     * @see #performRag(RAGRequest) for retrieval-focused operations
     */
    RAGResponse performRAGQuery(RAGRequest request);
    
    // =========================================================================
    // Content Indexing Operations
    // =========================================================================
    
    /**
     * Index content for RAG retrieval.
     * 
     * <p>This method generates embeddings for the provided content and stores
     * them in the vector database for later retrieval.</p>
     * 
     * <p><strong>Process:</strong></p>
     * <ol>
     *   <li>Generate embedding for the content</li>
     *   <li>Store embedding with content and metadata in vector database</li>
     *   <li>Associate with entity type and ID for filtering</li>
     * </ol>
     * 
     * @param entityType the type of entity (e.g., "product", "document", "faq")
     * @param entityId unique identifier for the entity
     * @param content the text content to index
     * @param metadata additional metadata to store with the embedding
     * @throws IllegalArgumentException if entityType, entityId, or content is null/blank
     */
    void indexContent(String entityType, String entityId, String content, Map<String, Object> metadata);
    
    /**
     * Remove content from RAG index.
     * 
     * <p>This method removes the embedding and associated data for the
     * specified entity from the vector database.</p>
     * 
     * @param entityType the type of entity
     * @param entityId unique identifier for the entity
     * @throws IllegalArgumentException if entityType or entityId is null/blank
     */
    void removeContent(String entityType, String entityId);
    
    // =========================================================================
    // Statistics and Monitoring
    // =========================================================================
    
    /**
     * Get RAG statistics.
     * 
     * <p>Returns statistics about the RAG system including indexed document
     * counts, vector database metrics, and performance data.</p>
     * 
     * @return map of statistics; never null
     */
    Map<String, Object> getStatistics();
    
    // =========================================================================
    // Default Methods
    // =========================================================================
    
    /**
     * Check if the RAG provider is available and ready.
     * 
     * <p>Default implementation returns true. Implementations may override
     * to check vector database connectivity, embedding service availability, etc.</p>
     * 
     * @return true if the provider is ready to accept requests
     */
    default boolean isAvailable() {
        return true;
    }
    
    /**
     * Get the provider name for logging and monitoring.
     * 
     * @return provider name; never null
     */
    default String getProviderName() {
        return "default";
    }
}
