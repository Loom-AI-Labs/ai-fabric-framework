package com.ai.infrastructure.embedding;

import com.ai.infrastructure.dto.AIEmbeddingRequest;
import com.ai.infrastructure.dto.AIEmbeddingResponse;

import java.util.List;
import java.util.Map;

/**
 * Embedding Provider Interface
 * 
 * Abstraction for different embedding generation methods:
 * - ONNX Runtime (local, no dependencies)
 * - REST API (Docker/sentence-transformers container)
 * - OpenAI API (cloud service)
 * 
 * This allows swapping between providers via configuration
 * without changing application code, similar to VectorDatabaseService.
 * 
 * @author AI Infrastructure Team
 * @version 2.0.0
 */
public interface EmbeddingProvider {
    
    /**
     * Get provider name
     * 
     * @return provider name (e.g., "onnx", "rest", "openai")
     */
    String getProviderName();
    
    /**
     * Check if provider is available
     * 
     * @return true if provider is available and ready to use
     */
    boolean isAvailable();
    
    /**
     * Generate embedding for text content
     * 
     * @param request embedding request with text and model
     * @return embedding response with vector data
     */
    AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request);
    
    /**
     * Generate embeddings for multiple texts in batch
     * Batch processing is typically faster than individual calls
     * 
     * @param texts list of texts to embed
     * @return list of embedding responses
     */
    List<AIEmbeddingResponse> generateEmbeddings(List<String> texts);
    
    /**
     * Get embedding dimension for the configured model
     * 
     * @return dimension of embedding vectors (e.g., 384, 768, 1536)
     */
    int getEmbeddingDimension();
    
    /**
     * Get provider status and health
     * 
     * @return status map with health info, latency, etc.
     */
    Map<String, Object> getStatus();
}


