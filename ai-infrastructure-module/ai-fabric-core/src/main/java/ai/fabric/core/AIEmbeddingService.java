package ai.fabric.core;

import ai.fabric.cache.AICacheNames;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.config.AIProviderConfig;
import ai.fabric.embedding.EmbeddingProvider;
import ai.fabric.exception.AIServiceException;
import ai.fabric.provider.ProviderRequestOverrideSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for AI embedding generation
 * 
 * This service handles the generation of vector embeddings for text content
 * using swappable embedding providers (ONNX, OpenAI, Azure, Cohere, Gemini).
 * It provides caching and batch processing capabilities for efficient embedding generation.
 * 
 * Uses EmbeddingProvider abstraction for easy swapping between providers:
 * - ONNX: Local bundled embeddings
 * - OpenAI-compatible / cloud providers: external embedding APIs
 * 
 * @author AI Infrastructure Team
 * @version 2.0.0
 */
@Slf4j
// @Service // Removed - already defined as @Bean in AIInfrastructureAutoConfiguration
public class AIEmbeddingService {

    public record EmbeddingExecution(
        AIEmbeddingResponse response,
        boolean cacheHit,
        String providerName,
        String effectiveModel,
        Long providerProcessingTimeMs,
        Long serviceProcessingTimeMs
    ) {
    }

    private record GeneratedEmbedding(
        AIEmbeddingResponse response,
        long serviceProcessingTimeMs
    ) {
    }
    
    private final AIProviderConfig config;
    private final EmbeddingProvider embeddingProvider;
    private final CacheManager cacheManager;
    private final EmbeddingProvider fallbackEmbeddingProvider;
    private final boolean fallbackEnabled;
    
    // Performance metrics
    private final AtomicLong totalEmbeddingsGenerated = new AtomicLong(0);
    private final AtomicLong totalProcessingTime = new AtomicLong(0);
    private final ConcurrentHashMap<String, Long> cacheHits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> cacheMisses = new ConcurrentHashMap<>();

    public AIEmbeddingService(AIProviderConfig config,
                              EmbeddingProvider embeddingProvider,
                              CacheManager cacheManager,
                              @Nullable EmbeddingProvider fallbackEmbeddingProvider) {
        this.config = config;
        this.embeddingProvider = embeddingProvider;
        this.cacheManager = cacheManager;
        this.fallbackEmbeddingProvider = fallbackEmbeddingProvider;
        this.fallbackEnabled = config.getEnableFallback() != null ? config.getEnableFallback() : true;
    }
    
    /**
     * Generate embedding for text content with caching
     * 
 * Delegates to the configured EmbeddingProvider implementation.
     * 
     * @param request the embedding request
     * @return embedding response with vector data
     */
    public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
        return executeEmbedding(request).response();
    }

    public EmbeddingExecution executeEmbedding(AIEmbeddingRequest request) {
        String providerName = embeddingProvider != null ? embeddingProvider.getProviderName() : "unknown";
        String effectiveModel = resolveEffectiveModel(request, providerName);
        String cacheKey = buildCacheKey(request, effectiveModel, providerName);
        Cache cache = getEmbeddingCache();

        AIEmbeddingResponse cachedResponse = getFromCache(cache, cacheKey);
        if (cachedResponse != null) {
            recordCacheHit(providerName);
            return new EmbeddingExecution(
                cachedResponse,
                true,
                providerName,
                effectiveModel,
                null,
                0L
            );
        }

        try {
            GeneratedEmbedding generated = generateAndCache(request, embeddingProvider, cache, cacheKey);
            AIEmbeddingResponse response = generated.response();
            return new EmbeddingExecution(
                response,
                false,
                providerName,
                effectiveModel,
                response != null ? response.getProcessingTimeMs() : null,
                generated.serviceProcessingTimeMs()
            );
        } catch (AIServiceException primaryException) {
            log.warn("Primary embedding provider {} failed: {}", providerName, primaryException.getMessage());
            if (!fallbackEnabled || fallbackEmbeddingProvider == null || !fallbackEmbeddingProvider.isAvailable()) {
                throw primaryException;
            }

            String fallbackName = fallbackEmbeddingProvider.getProviderName();
            log.info("Falling back to embedding provider: {}", fallbackName);

            String fallbackEffectiveModel = resolveEffectiveModel(request, fallbackName);
            String fallbackCacheKey = buildCacheKey(request, fallbackEffectiveModel, fallbackName);
            AIEmbeddingResponse fallbackCached = getFromCache(cache, fallbackCacheKey);
            if (fallbackCached != null) {
                recordCacheHit(fallbackName);
                return new EmbeddingExecution(
                    fallbackCached,
                    true,
                    fallbackName,
                    fallbackEffectiveModel,
                    null,
                    0L
                );
            }

            try {
                GeneratedEmbedding generated = generateAndCache(request, fallbackEmbeddingProvider, cache, fallbackCacheKey);
                AIEmbeddingResponse fallbackResponse = generated.response();
                return new EmbeddingExecution(
                    fallbackResponse,
                    false,
                    fallbackName,
                    fallbackEffectiveModel,
                    fallbackResponse != null ? fallbackResponse.getProcessingTimeMs() : null,
                    generated.serviceProcessingTimeMs()
                );
            } catch (AIServiceException fallbackException) {
                log.error("Fallback embedding provider {} also failed", fallbackName, fallbackException);
                throw fallbackException;
            }
        }
    }
    
    /**
     * Generate embeddings for multiple texts in batch with optimization
     * 
     * Delegates to configured EmbeddingProvider for batch processing
     * 
     * @param texts list of texts to embed
     * @param entityType type of entity for context
     * @return list of embedding responses
     */
    public List<AIEmbeddingResponse> generateEmbeddings(List<String> texts, String entityType) {
        try {
            if (embeddingProvider == null || !embeddingProvider.isAvailable()) {
                throw new AIServiceException("Embedding provider is not available");
            }
            
            log.debug("Generating {} embeddings using {} provider", 
                     texts.size(), embeddingProvider.getProviderName());
            
            long startTime = System.currentTimeMillis();
            
            // Delegate to embedding provider for batch processing
            List<AIEmbeddingResponse> responses = embeddingProvider.generateEmbeddings(texts);
            
            long processingTime = System.currentTimeMillis() - startTime;
            
            // Update metrics
            totalEmbeddingsGenerated.addAndGet(texts.size());
            totalProcessingTime.addAndGet(processingTime);
            
            log.debug("Successfully generated {} embeddings in {}ms using {} provider", 
                responses.size(), processingTime, embeddingProvider.getProviderName());
            
            return responses;
                
        } catch (Exception e) {
            log.error("Error generating batch embeddings", e);
            throw new AIServiceException("Failed to generate batch embeddings", e);
        }
    }
    
    /**
     * Preprocess text for embedding generation
     * 
     * @param text the text to preprocess
     * @return preprocessed text
     */
    public String preprocessText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        
        // Basic text preprocessing
        return text.trim()
            .replaceAll("\\s+", " ") // Replace multiple spaces with single space
            .replaceAll("[\\r\\n]+", " ") // Replace line breaks with spaces
            .substring(0, Math.min(text.length(), 8000)); // Limit to 8000 characters
    }
    
    /**
     * Chunk text for embedding generation
     * 
     * @param text the text to chunk
     * @param maxChunkSize maximum size of each chunk
     * @return list of text chunks
     */
    public List<String> chunkText(String text, int maxChunkSize) {
        if (text == null || text.trim().isEmpty()) {
            return List.of();
        }
        
        String preprocessed = preprocessText(text);
        
        if (preprocessed.length() <= maxChunkSize) {
            return List.of(preprocessed);
        }
        
        // Simple chunking by sentences
        String[] sentences = preprocessed.split("[.!?]+");
        List<String> chunks = new java.util.ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        
        for (String sentence : sentences) {
            if (currentChunk.length() + sentence.length() + 1 <= maxChunkSize) {
                if (currentChunk.length() > 0) {
                    currentChunk.append(" ");
                }
                currentChunk.append(sentence.trim());
            } else {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString());
                    currentChunk = new StringBuilder(sentence.trim());
                }
            }
        }
        
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }
        
        return chunks;
    }
    
    /**
     * Generate embeddings asynchronously for better performance
     * 
     * @param request the embedding request
     * @return CompletableFuture with embedding response
     */
    @Async
    public CompletableFuture<AIEmbeddingResponse> generateEmbeddingAsync(AIEmbeddingRequest request) {
        try {
            AIEmbeddingResponse response = generateEmbedding(request);
            return CompletableFuture.completedFuture(response);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }
    
    /**
     * Generate embeddings for multiple texts asynchronously
     * 
     * @param texts list of texts to embed
     * @param entityType type of entity for context
     * @return CompletableFuture with list of embedding responses
     */
    @Async
    public CompletableFuture<List<AIEmbeddingResponse>> generateEmbeddingsAsync(List<String> texts, String entityType) {
        try {
            List<AIEmbeddingResponse> responses = generateEmbeddings(texts, entityType);
            return CompletableFuture.completedFuture(responses);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }
    
    /**
     * Get performance metrics for monitoring
     * 
     * @return map of performance metrics
     */
    public java.util.Map<String, Object> getPerformanceMetrics() {
        long totalEmbeddings = totalEmbeddingsGenerated.get();
        long totalTime = totalProcessingTime.get();
        double avgProcessingTime = totalEmbeddings > 0 ? (double) totalTime / totalEmbeddings : 0.0;
        
        return java.util.Map.of(
            "totalEmbeddingsGenerated", totalEmbeddings,
            "totalProcessingTimeMs", totalTime,
            "averageProcessingTimeMs", avgProcessingTime,
            "cacheHits", cacheHits,
            "cacheMisses", cacheMisses
        );
    }
    
    /**
     * Clear performance metrics
     */
    public void clearMetrics() {
        totalEmbeddingsGenerated.set(0);
        totalProcessingTime.set(0);
        cacheHits.clear();
        cacheMisses.clear();
    }

    private Cache getEmbeddingCache() {
        return cacheManager != null ? cacheManager.getCache(AICacheNames.EMBEDDINGS) : null;
    }

    private AIEmbeddingResponse getFromCache(Cache cache, String cacheKey) {
        if (cache == null) {
            return null;
        }
        return cache.get(cacheKey, AIEmbeddingResponse.class);
    }

    private GeneratedEmbedding generateAndCache(AIEmbeddingRequest request,
                                                EmbeddingProvider provider,
                                                Cache cache,
                                                String cacheKey) {
        if (provider == null || !provider.isAvailable()) {
            throw new AIServiceException("Embedding provider is not available. Provider: " +
                (provider != null ? provider.getProviderName() : "null"));
        }

        String providerName = provider.getProviderName();
        log.debug("Generating embedding using {} provider for text: {}", providerName, request.getText());

        long startTime = System.currentTimeMillis();
        AIEmbeddingResponse response = provider.generateEmbedding(request);
        long processingTime = System.currentTimeMillis() - startTime;

        totalEmbeddingsGenerated.incrementAndGet();
        totalProcessingTime.addAndGet(processingTime);
        recordCacheMiss(providerName);

        if (cache != null) {
            cache.put(cacheKey, response);
        }

        log.debug("Successfully generated embedding with {} dimensions in {}ms using {} provider",
            response.getDimensions(), processingTime, providerName);

        return new GeneratedEmbedding(response, processingTime);
    }

    private void recordCacheHit(String providerName) {
        cacheHits.merge(providerName, 1L, Long::sum);
    }

    private void recordCacheMiss(String providerName) {
        cacheMisses.merge(providerName, 1L, Long::sum);
    }

    private String resolveEffectiveModel(AIEmbeddingRequest request, String providerName) {
        if (request != null && request.getModel() != null && !request.getModel().isBlank()) {
            return request.getModel().trim();
        }

        AIProviderConfig.EmbeddingDefaults defaults = config != null ? config.resolveEmbeddingDefaults() : null;
        if (defaults != null
            && defaults.providerName() != null
            && defaults.providerName().equalsIgnoreCase(providerName)
            && defaults.model() != null
            && !defaults.model().isBlank()) {
            return defaults.model().trim();
        }

        return providerName != null ? providerName : "default";
    }

    private String buildCacheKey(AIEmbeddingRequest request, String effectiveModel, String providerName) {
        String text = request != null && request.getText() != null ? request.getText() : "";
        String model = effectiveModel != null && !effectiveModel.isBlank() ? effectiveModel : "default";
        String provider = providerName != null && !providerName.isBlank() ? providerName : "unknown";
        String connection = ProviderRequestOverrideSupport.cacheDiscriminator(
            request != null ? request.getParameters() : null
        );
        return text + "_" + model + "_" + provider + "_" + connection;
    }
}
