package ai.fabric.provider;

import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationRequestContracts;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.config.AIProviderConfig;
import ai.fabric.exception.AIServiceException;
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
            if (isFallbackEnabled()
                && !TransientInputSupport.hasFileUrlInputs(request)
                && providerConfig.getGenerationFallbackProviders() != null
                && !providerConfig.getGenerationFallbackProviders().isEmpty()) {
                return tryFallbackProviders(
                    request,
                    configuredProvider,
                    new AIServiceException("Configured generation provider is unavailable")
                );
            }
            throw new AIServiceException(
                "Configured generation provider is not available: " + configuredProvider
            );
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
            
            return annotateGenerationResponse(
                response,
                selectedProvider.getProviderName(),
                selectedProvider.getProviderName(),
                false,
                List.of(providerAttempt(selectedProvider.getProviderName(), "SUCCEEDED"))
            );
            
        } catch (Exception e) {
            boolean fallbackAllowed = isFallbackEnabled()
                && !TransientInputSupport.hasFileUrlInputs(request);
            log.error(
                "Provider {} failed for content generation; fallbackAllowed={} cause={}",
                selectedProvider.getProviderName(),
                fallbackAllowed,
                e.getClass().getSimpleName()
            );
            updateProviderStatus(selectedProvider.getProviderName(), false);
            
            // Try fallback providers
            if (!fallbackAllowed) {
                throw e;
            }
            return tryFallbackProviders(request, selectedProvider.getProviderName(), e);
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
            if (isFallbackEnabled()
                && providerConfig.getEmbeddingFallbackProviders() != null
                && !providerConfig.getEmbeddingFallbackProviders().isEmpty()) {
                return tryFallbackEmbedding(
                    request,
                    configuredProvider,
                    new AIServiceException("Configured embedding provider is unavailable")
                );
            }
            throw new AIServiceException(
                "Configured embedding provider is not available: " + configuredProvider
            );
        }
        
        try {
            log.debug("Using provider: {} for embedding generation", selectedProvider.getProviderName());
            AIEmbeddingResponse response = selectedProvider.generateEmbedding(request);
            
            // Update provider status
            updateProviderStatus(selectedProvider.getProviderName(), true);
            
            return annotateEmbeddingResponse(
                response,
                selectedProvider.getProviderName(),
                selectedProvider.getProviderName(),
                false,
                List.of(providerAttempt(selectedProvider.getProviderName(), "SUCCEEDED"))
            );
            
        } catch (Exception e) {
            boolean fallbackAllowed = isFallbackEnabled();
            log.error(
                "Provider {} failed for embedding generation; fallbackAllowed={} cause={}",
                selectedProvider.getProviderName(),
                fallbackAllowed,
                e.getClass().getSimpleName()
            );
            updateProviderStatus(selectedProvider.getProviderName(), false);
            
            // Try fallback providers
            if (!fallbackAllowed) {
                throw e;
            }
            return tryFallbackEmbedding(request, selectedProvider.getProviderName(), e);
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
     * Try fallback providers for content generation
     * 
     * @param request generation request
     * @param availableProviders available providers
     * @param failedProvider failed provider
     * @return generation response
     */
    private AIGenerationResponse tryFallbackProviders(AIGenerationRequest request,
                                                      String failedProviderName,
                                                      Exception primaryFailure) {
        List<Map<String, Object>> attempts = new ArrayList<>();
        attempts.add(providerAttempt(failedProviderName, "FAILED"));
        Exception lastFailure = primaryFailure;
        for (AIProvider provider : resolveNamedFallbackProviders(
            providerConfig.getGenerationFallbackProviders(),
            failedProviderName,
            false
        )) {
            try {
                log.debug("Trying fallback provider: {}", provider.getProviderName());
                AIGenerationResponse response = provider.generateContent(request);
                if (TransientInputSupport.requiresDocumentUsage(request)
                    && !TransientInputSupport.hasDocumentUsage(response)) {
                    attempts.add(providerAttempt(provider.getProviderName(), "REJECTED_DOCUMENT_USAGE_MISSING"));
                    updateProviderStatus(provider.getProviderName(), false);
                    continue;
                }
                updateProviderStatus(provider.getProviderName(), true);
                attempts.add(providerAttempt(provider.getProviderName(), "SUCCEEDED"));
                return annotateGenerationResponse(
                    response,
                    failedProviderName,
                    provider.getProviderName(),
                    true,
                    attempts
                );
            } catch (Exception e) {
                lastFailure = e;
                log.warn(
                    "Fallback provider {} failed for content generation; cause={}",
                    provider.getProviderName(),
                    e.getClass().getSimpleName()
                );
                updateProviderStatus(provider.getProviderName(), false);
                attempts.add(providerAttempt(provider.getProviderName(), "FAILED"));
            }
        }

        throw new AIServiceException("Configured generation provider chain failed", lastFailure);
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
                                                     String failedProviderName,
                                                     Exception primaryFailure) {
        List<Map<String, Object>> attempts = new ArrayList<>();
        attempts.add(providerAttempt(failedProviderName, "FAILED"));
        Exception lastFailure = primaryFailure;
        for (AIProvider provider : resolveNamedFallbackProviders(
            providerConfig.getEmbeddingFallbackProviders(),
            failedProviderName,
            true
        )) {
            try {
                log.debug("Trying fallback provider: {} for embedding", provider.getProviderName());
                AIEmbeddingResponse response = provider.generateEmbedding(request);
                updateProviderStatus(provider.getProviderName(), true);
                attempts.add(providerAttempt(provider.getProviderName(), "SUCCEEDED"));
                return annotateEmbeddingResponse(
                    response,
                    failedProviderName,
                    provider.getProviderName(),
                    true,
                    attempts
                );
            } catch (Exception e) {
                lastFailure = e;
                log.warn(
                    "Fallback provider {} failed for embedding generation; cause={}",
                    provider.getProviderName(),
                    e.getClass().getSimpleName()
                );
                updateProviderStatus(provider.getProviderName(), false);
                attempts.add(providerAttempt(provider.getProviderName(), "FAILED"));
            }
        }

        throw new AIServiceException("Configured embedding provider chain failed", lastFailure);
    }

    private List<AIProvider> resolveNamedFallbackProviders(List<String> configuredChain,
                                                           String primaryProviderName,
                                                           boolean requireEmbeddingCompatibility) {
        if (!isFallbackEnabled()
            || primaryProviderName == null
            || configuredChain == null
            || configuredChain.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> names = configuredChain.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(name -> !name.isEmpty())
            .filter(name -> !name.equalsIgnoreCase(primaryProviderName))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        List<AIProvider> resolved = new ArrayList<>();
        for (String name : names) {
            AIProvider provider = findRegisteredProvider(name);
            if (provider == null || !provider.isAvailable()) {
                log.warn("Configured fallback provider '{}' is unavailable", name);
                continue;
            }
            if (!hasCompatibleDataPolicy(primaryProviderName, provider.getProviderName())) {
                log.warn(
                    "Configured fallback transition {} -> {} denied because data-policy labels are absent or differ",
                    primaryProviderName,
                    provider.getProviderName()
                );
                continue;
            }
            if (requireEmbeddingCompatibility
                && !hasCompatibleEmbeddingSpace(primaryProviderName, provider.getProviderName())) {
                log.warn(
                    "Configured embedding fallback transition {} -> {} denied because embedding compatibility groups are absent or differ",
                    primaryProviderName,
                    provider.getProviderName()
                );
                continue;
            }
            resolved.add(provider);
        }
        return List.copyOf(resolved);
    }

    private boolean hasCompatibleDataPolicy(String primaryProvider, String fallbackProvider) {
        Map<String, String> labels = providerConfig.getProviderDataPolicies();
        if (labels == null || labels.isEmpty()) {
            return false;
        }
        String primary = dataPolicyLabel(labels, primaryProvider);
        String fallback = dataPolicyLabel(labels, fallbackProvider);
        return primary != null && primary.equals(fallback);
    }

    private boolean hasCompatibleEmbeddingSpace(String primaryProvider, String fallbackProvider) {
        Map<String, String> groups = providerConfig.getEmbeddingCompatibilityGroups();
        if (groups == null || groups.isEmpty()) {
            return false;
        }
        String primary = dataPolicyLabel(groups, primaryProvider);
        String fallback = dataPolicyLabel(groups, fallbackProvider);
        return primary != null && primary.equals(fallback);
    }

    private String dataPolicyLabel(Map<String, String> labels, String providerName) {
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

    private AIGenerationResponse annotateGenerationResponse(AIGenerationResponse response,
                                                            String originalProvider,
                                                            String effectiveProvider,
                                                            boolean transitioned,
                                                            List<Map<String, Object>> attempts) {
        if (response == null) {
            throw new AIServiceException("AI provider returned no generation response");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (response.getMetadata() != null) {
            metadata.putAll(response.getMetadata());
        }
        metadata.put("providerRecovery", providerRecovery(
            originalProvider,
            effectiveProvider,
            transitioned,
            attempts
        ));
        response.setMetadata(Collections.unmodifiableMap(metadata));
        return response;
    }

    private AIEmbeddingResponse annotateEmbeddingResponse(AIEmbeddingResponse response,
                                                          String originalProvider,
                                                          String effectiveProvider,
                                                          boolean transitioned,
                                                          List<Map<String, Object>> attempts) {
        if (response == null) {
            throw new AIServiceException("AI provider returned no embedding response");
        }
        response.setProvider(effectiveProvider);
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (response.getMetadata() != null) {
            metadata.putAll(response.getMetadata());
        }
        Map<String, Object> recovery = new LinkedHashMap<>(providerRecovery(
            originalProvider,
            effectiveProvider,
            transitioned,
            attempts
        ));
        if (transitioned) {
            String compatibilityGroup = dataPolicyLabel(
                providerConfig.getEmbeddingCompatibilityGroups(),
                effectiveProvider
            );
            if (compatibilityGroup != null) {
                recovery.put("embeddingCompatibilityGroup", compatibilityGroup);
            }
        }
        metadata.put("providerRecovery", Collections.unmodifiableMap(recovery));
        response.setMetadata(Collections.unmodifiableMap(metadata));
        return response;
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
        recovery.put("attempts", attempts != null ? List.copyOf(attempts) : List.of());
        return Collections.unmodifiableMap(recovery);
    }

    private Map<String, Object> providerAttempt(String provider, String status) {
        return Map.of(
            "provider", provider != null ? provider : "unknown",
            "status", status
        );
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
        return Boolean.TRUE.equals(providerConfig.getEnableFallback());
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
