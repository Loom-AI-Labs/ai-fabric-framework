package com.ai.infrastructure.provider;

import com.ai.infrastructure.dto.AIGenerationRequest;
import com.ai.infrastructure.dto.AIGenerationResponse;
import com.ai.infrastructure.dto.AIEmbeddingRequest;
import com.ai.infrastructure.dto.AIEmbeddingResponse;

/**
 * AI Provider Interface
 * 
 * Common interface for all AI providers (OpenAI, Anthropic, Cohere, etc.).
 * Provides abstraction for content generation and embedding services.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
public interface AIProvider {
    
    /**
     * Get provider name
     * 
     * @return provider name
     */
    String getProviderName();
    
    /**
     * Check if provider is available
     * 
     * @return true if available
     */
    boolean isAvailable();
    
    /**
     * Generate content using the provider
     * 
     * @param request generation request
     * @return generation response
     */
    AIGenerationResponse generateContent(AIGenerationRequest request);
    
    /**
     * Generate embeddings using the provider
     * 
     * @param request embedding request
     * @return embedding response
     */
    AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request);
    
    /**
     * Get provider status
     * 
     * @return provider status
     */
    ProviderStatus getStatus();
    
    /**
     * Get provider configuration
     * 
     * @return provider configuration
     */
    ProviderConfig getConfig();
}