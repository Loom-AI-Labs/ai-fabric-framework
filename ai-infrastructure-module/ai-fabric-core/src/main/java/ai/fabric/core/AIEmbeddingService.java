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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
        Long serviceProcessingTimeMs,
        Map<String, Object> providerRecovery
    ) {
        public EmbeddingExecution {
            providerRecovery = providerRecovery != null ? Map.copyOf(providerRecovery) : Map.of();
        }

        public EmbeddingExecution(AIEmbeddingResponse response,
                                  boolean cacheHit,
                                  String providerName,
                                  String effectiveModel,
                                  Long providerProcessingTimeMs,
                                  Long serviceProcessingTimeMs) {
            this(
                response,
                cacheHit,
                providerName,
                effectiveModel,
                providerProcessingTimeMs,
                serviceProcessingTimeMs,
                Map.of()
            );
        }
    }

    private record GeneratedEmbedding(
        AIEmbeddingResponse response,
        long serviceProcessingTimeMs
    ) {
    }
    
    private final AIProviderConfig config;
    private final EmbeddingProvider embeddingProvider;
    private final CacheManager cacheManager;
    private final Map<String, EmbeddingProvider> embeddingProvidersByName;
    
    // Performance metrics
    private final AtomicLong totalEmbeddingsGenerated = new AtomicLong(0);
    private final AtomicLong totalProcessingTime = new AtomicLong(0);
    private final ConcurrentHashMap<String, Long> cacheHits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> cacheMisses = new ConcurrentHashMap<>();

    public AIEmbeddingService(AIProviderConfig config,
                              EmbeddingProvider embeddingProvider,
                              CacheManager cacheManager,
                              @Nullable EmbeddingProvider fallbackEmbeddingProvider) {
        this(
            config,
            embeddingProvider,
            cacheManager,
            fallbackEmbeddingProvider,
            fallbackEmbeddingProvider != null
                ? List.of(embeddingProvider, fallbackEmbeddingProvider)
                : List.of(embeddingProvider)
        );
    }

    public AIEmbeddingService(AIProviderConfig config,
                              EmbeddingProvider embeddingProvider,
                              CacheManager cacheManager,
                              @Nullable EmbeddingProvider fallbackEmbeddingProvider,
                              List<EmbeddingProvider> embeddingProviders) {
        this.config = config;
        this.embeddingProvider = embeddingProvider;
        this.cacheManager = cacheManager;
        LinkedHashMap<String, EmbeddingProvider> providers = new LinkedHashMap<>();
        registerProvider(providers, embeddingProvider);
        if (embeddingProviders != null) {
            embeddingProviders.forEach(provider -> registerProvider(providers, provider));
        }
        registerProvider(providers, fallbackEmbeddingProvider);
        this.embeddingProvidersByName = Map.copyOf(providers);
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
            Map<String, Object> recovery = providerRecovery(providerName, providerName, false, List.of(
                providerAttempt(providerName, "CACHE_HIT")
            ));
            return new EmbeddingExecution(
                withProviderDiagnostics(cachedResponse, providerName, recovery),
                true,
                providerName,
                effectiveModel,
                null,
                0L,
                recovery
            );
        }

        try {
            GeneratedEmbedding generated = generateAndCache(request, embeddingProvider, cache, cacheKey);
            AIEmbeddingResponse response = generated.response();
            Map<String, Object> recovery = providerRecovery(providerName, providerName, false, List.of(
                providerAttempt(providerName, "SUCCEEDED")
            ));
            return new EmbeddingExecution(
                withProviderDiagnostics(response, providerName, recovery),
                false,
                providerName,
                effectiveModel,
                response != null ? response.getProcessingTimeMs() : null,
                generated.serviceProcessingTimeMs(),
                recovery
            );
        } catch (RuntimeException primaryException) {
            log.warn(
                "Primary embedding provider {} failed; cause={}",
                providerName,
                primaryException.getClass().getSimpleName()
            );
            List<EmbeddingProvider> fallbackProviders = resolveFallbackProviders(providerName);
            if (fallbackProviders.isEmpty()) {
                throw embeddingFailure("Primary embedding provider failed", primaryException);
            }

            List<Map<String, Object>> attempts = new ArrayList<>();
            attempts.add(providerAttempt(providerName, "FAILED"));
            RuntimeException lastFailure = primaryException;
            for (EmbeddingProvider fallbackProvider : fallbackProviders) {
                String fallbackName = fallbackProvider.getProviderName();
                log.info("Trying configured embedding fallback provider: {}", fallbackName);
                String fallbackEffectiveModel = resolveEffectiveModel(request, fallbackName);
                String fallbackCacheKey = buildCacheKey(request, fallbackEffectiveModel, fallbackName);
                AIEmbeddingResponse fallbackCached = getFromCache(cache, fallbackCacheKey);
                if (fallbackCached != null) {
                    recordCacheHit(fallbackName);
                    attempts.add(providerAttempt(fallbackName, "CACHE_HIT"));
                    Map<String, Object> recovery = providerRecovery(
                        providerName,
                        fallbackName,
                        true,
                        attempts
                    );
                    return new EmbeddingExecution(
                        withProviderDiagnostics(fallbackCached, fallbackName, recovery),
                        true,
                        fallbackName,
                        fallbackEffectiveModel,
                        null,
                        0L,
                        recovery
                    );
                }

                try {
                    GeneratedEmbedding generated = generateAndCache(
                        request,
                        fallbackProvider,
                        cache,
                        fallbackCacheKey
                    );
                    AIEmbeddingResponse fallbackResponse = generated.response();
                    attempts.add(providerAttempt(fallbackName, "SUCCEEDED"));
                    Map<String, Object> recovery = providerRecovery(
                        providerName,
                        fallbackName,
                        true,
                        attempts
                    );
                    return new EmbeddingExecution(
                        withProviderDiagnostics(fallbackResponse, fallbackName, recovery),
                        false,
                        fallbackName,
                        fallbackEffectiveModel,
                        fallbackResponse != null ? fallbackResponse.getProcessingTimeMs() : null,
                        generated.serviceProcessingTimeMs(),
                        recovery
                    );
                } catch (RuntimeException fallbackException) {
                    log.warn(
                        "Embedding fallback provider {} failed; cause={}",
                        fallbackName,
                        fallbackException.getClass().getSimpleName()
                    );
                    attempts.add(providerAttempt(fallbackName, "FAILED"));
                    if (lastFailure != fallbackException) {
                        fallbackException.addSuppressed(lastFailure);
                    }
                    lastFailure = fallbackException;
                }
            }
            throw new AIServiceException("Configured embedding provider chain failed", lastFailure);
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
        String primaryName = embeddingProvider != null ? embeddingProvider.getProviderName() : "unknown";
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

            Map<String, Object> recovery = providerRecovery(primaryName, primaryName, false, List.of(
                providerAttempt(primaryName, "SUCCEEDED")
            ));
            return withProviderDiagnostics(responses, primaryName, recovery);
        } catch (RuntimeException primaryException) {
            log.warn(
                "Primary batch embedding provider {} failed; cause={}",
                primaryName,
                primaryException.getClass().getSimpleName()
            );
            List<EmbeddingProvider> fallbackProviders = resolveFallbackProviders(primaryName);
            if (fallbackProviders.isEmpty()) {
                throw embeddingFailure("Failed to generate batch embeddings", primaryException);
            }

            List<Map<String, Object>> attempts = new ArrayList<>();
            attempts.add(providerAttempt(primaryName, "FAILED"));
            RuntimeException lastFailure = primaryException;
            for (EmbeddingProvider fallbackProvider : fallbackProviders) {
                String fallbackName = fallbackProvider.getProviderName();
                try {
                    long startTime = System.currentTimeMillis();
                    List<AIEmbeddingResponse> responses = fallbackProvider.generateEmbeddings(texts);
                    long processingTime = System.currentTimeMillis() - startTime;
                    totalEmbeddingsGenerated.addAndGet(texts.size());
                    totalProcessingTime.addAndGet(processingTime);
                    attempts.add(providerAttempt(fallbackName, "SUCCEEDED"));
                    Map<String, Object> recovery = providerRecovery(
                        primaryName,
                        fallbackName,
                        true,
                        attempts
                    );
                    return withProviderDiagnostics(responses, fallbackName, recovery);
                } catch (RuntimeException fallbackException) {
                    log.warn(
                        "Batch embedding fallback provider {} failed; cause={}",
                        fallbackName,
                        fallbackException.getClass().getSimpleName()
                    );
                    attempts.add(providerAttempt(fallbackName, "FAILED"));
                    if (lastFailure != fallbackException) {
                        fallbackException.addSuppressed(lastFailure);
                    }
                    lastFailure = fallbackException;
                }
            }
            throw new AIServiceException("Configured batch embedding provider chain failed", lastFailure);
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
        
        String normalized = text.trim()
            .replaceAll("\\s+", " ") // Replace multiple spaces with single space
            .replaceAll("[\\r\\n]+", " "); // Replace line breaks with spaces
        return normalized.substring(0, Math.min(normalized.length(), 8000));
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

    private List<EmbeddingProvider> resolveFallbackProviders(String primaryProviderName) {
        if (config == null
            || !Boolean.TRUE.equals(config.getEnableFallback())
            || primaryProviderName == null
            || config.getEmbeddingFallbackProviders() == null
            || config.getEmbeddingFallbackProviders().isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> configuredNames = config.getEmbeddingFallbackProviders().stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(name -> !name.isEmpty())
            .filter(name -> !name.equalsIgnoreCase(primaryProviderName))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<EmbeddingProvider> resolved = new ArrayList<>();
        for (String configuredName : configuredNames) {
            EmbeddingProvider provider = embeddingProvidersByName.get(normalizeProviderName(configuredName));
            if (provider == null || !provider.isAvailable()) {
                log.warn("Configured embedding fallback provider '{}' is unavailable", configuredName);
                continue;
            }
            if (!hasCompatibleDataPolicy(primaryProviderName, provider.getProviderName())) {
                log.warn(
                    "Embedding fallback transition {} -> {} denied because data-policy labels are absent or differ",
                    primaryProviderName,
                    provider.getProviderName()
                );
                continue;
            }
            if (!hasCompatibleEmbeddingSpace(primaryProviderName, provider.getProviderName())) {
                log.warn(
                    "Embedding fallback transition {} -> {} denied because embedding compatibility groups are absent or differ",
                    primaryProviderName,
                    provider.getProviderName()
                );
                continue;
            }
            resolved.add(provider);
        }
        return List.copyOf(resolved);
    }

    private boolean hasCompatibleDataPolicy(String primaryProviderName, String fallbackProviderName) {
        Map<String, String> labels = config.getProviderDataPolicies();
        String primary = providerLabel(labels, primaryProviderName);
        String fallback = providerLabel(labels, fallbackProviderName);
        return primary != null && primary.equals(fallback);
    }

    private boolean hasCompatibleEmbeddingSpace(String primaryProviderName, String fallbackProviderName) {
        Map<String, String> groups = config.getEmbeddingCompatibilityGroups();
        String primary = providerLabel(groups, primaryProviderName);
        String fallback = providerLabel(groups, fallbackProviderName);
        return primary != null && primary.equals(fallback);
    }

    private String providerLabel(Map<String, String> labels, String providerName) {
        if (labels == null || labels.isEmpty() || providerName == null) {
            return null;
        }
        return labels.entrySet().stream()
            .filter(entry -> entry.getKey() != null && entry.getValue() != null)
            .filter(entry -> providerName.equalsIgnoreCase(entry.getKey().trim()))
            .map(Map.Entry::getValue)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .findFirst()
            .orElse(null);
    }

    private void registerProvider(Map<String, EmbeddingProvider> providers, EmbeddingProvider provider) {
        if (providers == null || provider == null || provider.getProviderName() == null) {
            return;
        }
        String normalizedName = normalizeProviderName(provider.getProviderName());
        if (!normalizedName.isEmpty()) {
            providers.putIfAbsent(normalizedName, provider);
        }
    }

    private String normalizeProviderName(String providerName) {
        return providerName == null ? "" : providerName.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private Map<String, Object> providerRecovery(String originalProvider,
                                                 String effectiveProvider,
                                                 boolean transitioned,
                                                 List<Map<String, Object>> attempts) {
        Map<String, Object> recovery = new LinkedHashMap<>();
        recovery.put("policy", transitioned ? "NAMED_COMPATIBLE_CHAIN" : "PRIMARY_ONLY");
        recovery.put("originalProvider", originalProvider);
        recovery.put("effectiveProvider", effectiveProvider);
        recovery.put("transitioned", transitioned);
        if (transitioned) {
            String compatibilityGroup = providerLabel(
                config != null ? config.getEmbeddingCompatibilityGroups() : null,
                effectiveProvider
            );
            if (compatibilityGroup != null) {
                recovery.put("embeddingCompatibilityGroup", compatibilityGroup);
            }
        }
        recovery.put("attempts", attempts != null ? List.copyOf(attempts) : List.of());
        return Map.copyOf(recovery);
    }

    private Map<String, Object> providerAttempt(String provider, String status) {
        return Map.of(
            "provider", provider != null ? provider : "unknown",
            "status", status
        );
    }

    private List<AIEmbeddingResponse> withProviderDiagnostics(
        List<AIEmbeddingResponse> responses,
        String providerName,
        Map<String, Object> recovery
    ) {
        if (responses == null || responses.isEmpty()) {
            return List.of();
        }
        return responses.stream()
            .map(response -> withProviderDiagnostics(response, providerName, recovery))
            .toList();
    }

    private AIEmbeddingResponse withProviderDiagnostics(
        AIEmbeddingResponse response,
        String providerName,
        Map<String, Object> recovery
    ) {
        if (response == null) {
            throw new AIServiceException("Embedding provider returned a null response");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (response.getMetadata() != null) {
            metadata.putAll(response.getMetadata());
        }
        metadata.put("providerRecovery", recovery != null ? Map.copyOf(recovery) : Map.of());
        return AIEmbeddingResponse.builder()
            .embedding(response.getEmbedding())
            .model(response.getModel())
            .dimensions(response.getDimensions())
            .processingTimeMs(response.getProcessingTimeMs())
            .requestId(response.getRequestId())
            .provider(providerName)
            .metadata(Map.copyOf(metadata))
            .build();
    }

    private AIServiceException embeddingFailure(String message, RuntimeException cause) {
        return cause instanceof AIServiceException aiServiceException
            ? aiServiceException
            : new AIServiceException(message, cause);
    }
}
