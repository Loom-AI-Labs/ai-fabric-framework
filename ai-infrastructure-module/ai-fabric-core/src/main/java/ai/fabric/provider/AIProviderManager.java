package ai.fabric.provider;

import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationRequestContracts;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.config.AIProviderConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * AI Provider Manager
 * 
 * Manages multiple AI providers with load balancing, fallback mechanisms,
 * and provider selection strategies.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AIProviderManager {
    
    private final List<AIProvider> providers;
    private final AIProviderConfig providerConfig;
    private final Map<String, AIProvider> providerMap = new ConcurrentHashMap<>();
    private final Map<String, ProviderStatus> providerStatuses = new ConcurrentHashMap<>();
    
    /**
     * Initialize provider manager
     */
    @PostConstruct
    public void initialize() {
        log.info("Initializing AI Provider Manager with {} providers", providers.size());
        
        for (AIProvider provider : providers) {
            providerMap.put(provider.getProviderName(), provider);
            providerStatuses.put(provider.getProviderName(), provider.getStatus());
            log.debug("Registered provider: {}", provider.getProviderName());
        }
        
        log.info("AI Provider Manager initialized successfully");
    }
    
    /**
     * Generate content using the best available provider
     * 
     * @param request generation request
     * @return generation response
     */
    public AIGenerationResponse generateContent(AIGenerationRequest request) {
        return generateContent(request, null);
    }

    /**
     * Generate content using the best available provider, optionally overriding the configured LLM provider.
     *
     * @param request generation request
     * @param providerOverride optional provider name override (e.g. "cohere")
     * @return generation response
     */
    public AIGenerationResponse generateContent(AIGenerationRequest request, String providerOverride) {
        log.debug("Generating content with provider manager");
        AIGenerationRequestContracts.validateStandardChatPrompting(request);
        
        List<AIProvider> availableProviders = getAvailableProviders();
        String configuredProvider = providerOverride != null && !providerOverride.isBlank()
            ? providerOverride
            : providerConfig.getLlmProvider();
        AIProvider selectedProvider = selectPreferredProviderForRequest(request, configuredProvider, availableProviders);
        if (selectedProvider == null) {
            if (availableProviders.isEmpty()) {
                throw new RuntimeException("No AI providers available");
            }
            selectedProvider = selectProvider(availableProviders, "generation");
        }
        
        try {
            log.debug("Using provider: {} for content generation", selectedProvider.getProviderName());
            AIGenerationResponse response = selectedProvider.generateContent(request);
            if (TransientInputSupport.requiresDocumentUsage(request)
                && !TransientInputSupport.hasDocumentUsage(response)) {
                updateProviderStatus(selectedProvider.getProviderName(), false);
                return TransientInputSupport.unsupportedFileUrlResponse(
                    request,
                    selectedProvider.getProviderName(),
                    "Provider response did not include required documentUsage evidence for transient file inputs."
                );
            }
            
            // Update provider status
            updateProviderStatus(selectedProvider.getProviderName(), true);
            
            return response;
            
        } catch (Exception e) {
            log.error("Provider {} failed, trying fallback", selectedProvider.getProviderName(), e);
            updateProviderStatus(selectedProvider.getProviderName(), false);
            
            // Try fallback providers
            if (!isFallbackEnabled() || TransientInputSupport.hasFileUrlInputs(request)) {
                throw e;
            }
            return tryFallbackProviders(request, availableProviders, selectedProvider);
        }
    }
    
    /**
     * Generate embedding using the best available provider
     * 
     * @param request embedding request
     * @return embedding response
     */
    public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
        log.debug("Generating embedding with provider manager");
        
        List<AIProvider> availableProviders = getAvailableProviders();
        String configuredProvider = providerConfig.getEmbeddingProvider();
        AIProvider selectedProvider = selectPreferredProviderForRequest(request, configuredProvider, availableProviders);
        if (selectedProvider == null) {
            if (availableProviders.isEmpty()) {
                throw new RuntimeException("No AI providers available");
            }
            selectedProvider = selectProvider(availableProviders, "embedding");
        }
        
        try {
            log.debug("Using provider: {} for embedding generation", selectedProvider.getProviderName());
            AIEmbeddingResponse response = selectedProvider.generateEmbedding(request);
            
            // Update provider status
            updateProviderStatus(selectedProvider.getProviderName(), true);
            
            return response;
            
        } catch (Exception e) {
            log.error("Provider {} failed, trying fallback", selectedProvider.getProviderName(), e);
            updateProviderStatus(selectedProvider.getProviderName(), false);
            
            // Try fallback providers
            if (!isFallbackEnabled()) {
                throw e;
            }
            return tryFallbackEmbedding(request, availableProviders, selectedProvider);
        }
    }
    
    /**
     * Get all available providers
     * 
     * @return list of available providers
     */
    public List<AIProvider> getAvailableProviders() {
        return providers.stream()
            .filter(AIProvider::isAvailable)
            .collect(Collectors.toList());
    }
    
    /**
     * Get provider by name
     * 
     * @param providerName provider name
     * @return provider or null if not found
     */
    public AIProvider getProvider(String providerName) {
        return providerMap.get(providerName);
    }
    
    /**
     * Get all provider statuses
     * 
     * @return map of provider statuses
     */
    public Map<String, ProviderStatus> getAllProviderStatuses() {
        Map<String, ProviderStatus> statuses = new HashMap<>();
        for (AIProvider provider : providers) {
            statuses.put(provider.getProviderName(), provider.getStatus());
        }
        return statuses;
    }
    
    /**
     * Get provider statistics
     * 
     * @return provider statistics
     */
    public Map<String, Object> getProviderStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        int totalProviders = providers.size();
        int availableProviders = getAvailableProviders().size();
        int healthyProviders = (int) providers.stream()
            .map(AIProvider::getStatus)
            .filter(ProviderStatus::isHealthy)
            .count();
        
        stats.put("totalProviders", totalProviders);
        stats.put("availableProviders", availableProviders);
        stats.put("healthyProviders", healthyProviders);
        stats.put("unavailableProviders", totalProviders - availableProviders);
        stats.put("unhealthyProviders", availableProviders - healthyProviders);
        
        // Provider-specific statistics
        Map<String, Object> providerStats = new HashMap<>();
        for (AIProvider provider : providers) {
            ProviderStatus status = provider.getStatus();
            Map<String, Object> providerData = new HashMap<>();
            providerData.put("available", provider.isAvailable());
            providerData.put("healthy", status.isHealthy());
            providerData.put("totalRequests", status.getTotalRequests());
            providerData.put("successRate", status.getSuccessRate());
            providerData.put("averageResponseTime", status.getAverageResponseTime());
            providerStats.put(provider.getProviderName(), providerData);
        }
        stats.put("providers", providerStats);
        
        return stats;
    }
    
    /**
     * Select the best provider based on strategy
     * 
     * @param availableProviders available providers
     * @param operationType operation type (generation, embedding)
     * @return selected provider
     */
    private AIProvider selectProvider(List<AIProvider> availableProviders, String operationType) {
        if (availableProviders.isEmpty()) {
            throw new RuntimeException("No providers available");
        }
        
        // Strategy 1: Priority-based selection
        AIProvider priorityProvider = selectByPriority(availableProviders);
        if (priorityProvider != null) {
            return priorityProvider;
        }
        
        // Strategy 2: Health-based selection
        AIProvider healthProvider = selectByHealth(availableProviders);
        if (healthProvider != null) {
            return healthProvider;
        }
        
        // Strategy 3: Performance-based selection
        AIProvider performanceProvider = selectByPerformance(availableProviders);
        if (performanceProvider != null) {
            return performanceProvider;
        }
        
        // Fallback: Random selection
        return availableProviders.get(new Random().nextInt(availableProviders.size()));
    }
    
    /**
     * Select provider by priority
     * 
     * @param providers available providers
     * @return highest priority provider
     */
    private AIProvider selectByPriority(List<AIProvider> providers) {
        return providers.stream()
            .filter(p -> p.getConfig().getPriority() != null)
            .max(Comparator.comparing(p -> p.getConfig().getPriority()))
            .orElse(null);
    }
    
    /**
     * Select provider by health
     * 
     * @param providers available providers
     * @return healthiest provider
     */
    private AIProvider selectByHealth(List<AIProvider> providers) {
        return providers.stream()
            .filter(p -> p.getStatus().isHealthy())
            .max(Comparator.comparing(p -> p.getStatus().getSuccessRate()))
            .orElse(null);
    }
    
    /**
     * Select provider by performance
     * 
     * @param providers available providers
     * @return best performing provider
     */
    private AIProvider selectByPerformance(List<AIProvider> providers) {
        return providers.stream()
            .filter(p -> p.getStatus().getAverageResponseTime() > 0)
            .min(Comparator.comparing(p -> p.getStatus().getAverageResponseTime()))
            .orElse(null);
    }
    
    /**
     * Try fallback providers for content generation
     * 
     * @param request generation request
     * @param availableProviders available providers
     * @param failedProvider failed provider
     * @return generation response
     */
    private AIGenerationResponse tryFallbackProviders(AIGenerationRequest request, 
                                                    List<AIProvider> availableProviders, 
                                                    AIProvider failedProvider) {
        List<AIProvider> fallbackProviders = availableProviders.stream()
            .filter(p -> !p.getProviderName().equals(failedProvider.getProviderName()))
            .collect(Collectors.toList());
        
        for (AIProvider provider : fallbackProviders) {
            try {
                log.debug("Trying fallback provider: {}", provider.getProviderName());
                AIGenerationResponse response = provider.generateContent(request);
                updateProviderStatus(provider.getProviderName(), true);
                return response;
            } catch (Exception e) {
                log.warn("Fallback provider {} also failed", provider.getProviderName(), e);
                updateProviderStatus(provider.getProviderName(), false);
            }
        }
        
        throw new RuntimeException("All providers failed for content generation");
    }
    
    /**
     * Try fallback providers for embedding generation
     * 
     * @param request embedding request
     * @param availableProviders available providers
     * @param failedProvider failed provider
     * @return embedding response
     */
    private AIEmbeddingResponse tryFallbackEmbedding(AIEmbeddingRequest request, 
                                                   List<AIProvider> availableProviders, 
                                                   AIProvider failedProvider) {
        List<AIProvider> fallbackProviders = availableProviders.stream()
            .filter(p -> !p.getProviderName().equals(failedProvider.getProviderName()))
            .collect(Collectors.toList());
        
        for (AIProvider provider : fallbackProviders) {
            try {
                log.debug("Trying fallback provider: {} for embedding", provider.getProviderName());
                AIEmbeddingResponse response = provider.generateEmbedding(request);
                updateProviderStatus(provider.getProviderName(), true);
                return response;
            } catch (Exception e) {
                log.warn("Fallback provider {} also failed for embedding", provider.getProviderName(), e);
                updateProviderStatus(provider.getProviderName(), false);
            }
        }
        
        throw new RuntimeException("All providers failed for embedding generation");
    }
    
    private AIProvider findPreferredProvider(List<AIProvider> availableProviders, String preferredName) {
        if (preferredName == null || preferredName.isBlank()) {
            return null;
        }
        return availableProviders.stream()
            .filter(provider -> preferredName.equalsIgnoreCase(provider.getProviderName()))
            .findFirst()
            .orElse(null);
    }

    private AIProvider selectPreferredProviderForRequest(AIGenerationRequest request,
                                                         String preferredName,
                                                         List<AIProvider> availableProviders) {
        AIProvider availablePreferredProvider = findPreferredProvider(availableProviders, preferredName);
        if (availablePreferredProvider != null) {
            return availablePreferredProvider;
        }
        if (!hasTrustedConnectionOverride(request)) {
            return null;
        }
        AIProvider registeredProvider = findRegisteredProvider(preferredName);
        if (registeredProvider != null) {
            log.debug("Using request-scoped connection override for provider {}", registeredProvider.getProviderName());
        }
        return registeredProvider;
    }

    private AIProvider selectPreferredProviderForRequest(AIEmbeddingRequest request,
                                                         String preferredName,
                                                         List<AIProvider> availableProviders) {
        AIProvider availablePreferredProvider = findPreferredProvider(availableProviders, preferredName);
        if (availablePreferredProvider != null) {
            return availablePreferredProvider;
        }
        if (!hasTrustedConnectionOverride(request)) {
            return null;
        }
        AIProvider registeredProvider = findRegisteredProvider(preferredName);
        if (registeredProvider != null) {
            log.debug("Using request-scoped embedding connection override for provider {}",
                registeredProvider.getProviderName());
        }
        return registeredProvider;
    }

    private AIProvider findRegisteredProvider(String preferredName) {
        if (preferredName == null || preferredName.isBlank()) {
            return null;
        }
        AIProvider exactProvider = providerMap.get(preferredName);
        if (exactProvider != null) {
            return exactProvider;
        }
        return providers.stream()
            .filter(provider -> preferredName.equalsIgnoreCase(provider.getProviderName()))
            .findFirst()
            .orElse(null);
    }

    private boolean hasTrustedConnectionOverride(AIGenerationRequest request) {
        return request != null
            && ProviderRequestOverrideSupport.read(request.getParameters()).hasAny();
    }

    private boolean hasTrustedConnectionOverride(AIEmbeddingRequest request) {
        return request != null
            && ProviderRequestOverrideSupport.read(request.getParameters()).hasAny();
    }

    private boolean isFallbackEnabled() {
        return providerConfig.getEnableFallback() == null || providerConfig.getEnableFallback();
    }

    /**
     * Update provider status
     * 
     * @param providerName provider name
     * @param success success flag
     */
    private void updateProviderStatus(String providerName, boolean success) {
        ProviderStatus status = providerStatuses.get(providerName);
        if (status != null) {
            // Update the status (this would typically be done by the provider itself)
            // For now, we just log the update
            log.debug("Updated status for provider {}: success={}", providerName, success);
        }
    }
}
