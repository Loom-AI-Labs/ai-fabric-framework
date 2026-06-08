package com.ai.infrastructure.service;

import com.ai.infrastructure.config.AIProviderConfig;
import com.ai.infrastructure.config.AIServiceConfig;
import com.ai.infrastructure.dto.AIConfigurationDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI Configuration Service
 * 
 * This service manages AI configuration, feature toggles, and service settings.
 * It provides a centralized way to access and modify AI configuration.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Slf4j
public class AIConfigurationService {
    
    private final AIProviderConfig providerConfig;
    private final AIServiceConfig serviceConfig;
    
    @Autowired
    public AIConfigurationService(AIProviderConfig providerConfig, AIServiceConfig serviceConfig) {
        this.providerConfig = providerConfig;
        this.serviceConfig = serviceConfig;
    }
    
    /**
     * Get complete AI configuration
     * 
     * @return complete AI configuration
     */
    public AIConfigurationDto getConfiguration() {
        log.debug("Retrieving complete AI configuration");
        
        AIConfigurationDto config = new AIConfigurationDto();
        
        // Provider configuration
        config.setLlmProvider(providerConfig.getLlmProvider());
        config.setEmbeddingProvider(providerConfig.getEmbeddingProvider());
        config.setProviderDetails(buildProviderDetails());
        
        AIProviderConfig.OpenAIConfig openai = providerConfig.getOpenai();
        config.setOpenaiApiKey(openai.getApiKey());
        config.setOpenaiModel(openai.getModel());
        config.setOpenaiEmbeddingModel(openai.getEmbeddingModel());
        
        AIProviderConfig.PineconeConfig pinecone = providerConfig.getPinecone();
        config.setPineconeApiKey(pinecone.getApiKey());
        config.setPineconeEnvironment(pinecone.getEnvironment());
        config.setPineconeIndexName(pinecone.getIndexName());
        
        // Service configuration
        config.setEnabled(serviceConfig.getEnabled());
        config.setAutoConfiguration(serviceConfig.getAutoConfiguration());
        config.setCachingEnabled(serviceConfig.getCachingEnabled());
        config.setMetricsEnabled(serviceConfig.getMetricsEnabled());
        config.setHealthChecksEnabled(serviceConfig.getHealthChecksEnabled());
        config.setLoggingEnabled(serviceConfig.getLoggingEnabled());
        config.setDefaultTimeout(serviceConfig.getDefaultTimeout());
        config.setMaxRetries(serviceConfig.getMaxRetries());
        config.setRetryDelay(serviceConfig.getRetryDelay());
        config.setAsyncEnabled(serviceConfig.getAsyncEnabled());
        config.setThreadPoolSize(serviceConfig.getThreadPoolSize());
        config.setBatchProcessingEnabled(serviceConfig.getBatchProcessingEnabled());
        config.setBatchSize(serviceConfig.getBatchSize());
        config.setRateLimitingEnabled(serviceConfig.getRateLimitingEnabled());
        config.setRateLimitPerMinute(serviceConfig.getRateLimitPerMinute());
        config.setCircuitBreakerEnabled(serviceConfig.getCircuitBreakerEnabled());
        config.setCircuitBreakerThreshold(serviceConfig.getCircuitBreakerThreshold());
        config.setCircuitBreakerTimeout(serviceConfig.getCircuitBreakerTimeout());
        config.setFeatureFlagsEnabled(serviceConfig.getFeatureFlagsEnabled());
        config.setFeatureFlags(serviceConfig.getFeatureFlags());
        config.setServices((Map<String, Object>) (Map<?, ?>) serviceConfig.getServices());
        
        log.debug("Successfully retrieved AI configuration");
        return config;
    }
    
    /**
     * Get provider configuration
     * 
     * @return provider configuration
     */
    public Map<String, Object> getProviderConfiguration() {
        log.debug("Retrieving provider configuration");
        
        Map<String, Object> config = new HashMap<>();
        config.put("llmProvider", providerConfig.getLlmProvider());
        config.put("embeddingProvider", providerConfig.getEmbeddingProvider());
        config.put("providerDetails", buildProviderDetails());
        
        AIProviderConfig.OpenAIConfig openai = providerConfig.getOpenai();
        config.put("openaiApiKey", openai.getApiKey());
        config.put("openaiModel", openai.getModel());
        config.put("openaiEmbeddingModel", openai.getEmbeddingModel());
        
        AIProviderConfig.PineconeConfig pinecone = providerConfig.getPinecone();
        config.put("pineconeApiKey", pinecone.getApiKey());
        config.put("pineconeEnvironment", pinecone.getEnvironment());
        config.put("pineconeIndexName", pinecone.getIndexName());
        
        return config;
    }
    
    /**
     * Get service configuration
     * 
     * @return service configuration
     */
    public Map<String, Object> getServiceConfiguration() {
        log.debug("Retrieving service configuration");
        
        Map<String, Object> config = new HashMap<>();
        config.put("enabled", serviceConfig.getEnabled());
        config.put("autoConfiguration", serviceConfig.getAutoConfiguration());
        config.put("cachingEnabled", serviceConfig.getCachingEnabled());
        config.put("metricsEnabled", serviceConfig.getMetricsEnabled());
        config.put("healthChecksEnabled", serviceConfig.getHealthChecksEnabled());
        config.put("loggingEnabled", serviceConfig.getLoggingEnabled());
        config.put("defaultTimeout", serviceConfig.getDefaultTimeout());
        config.put("maxRetries", serviceConfig.getMaxRetries());
        config.put("retryDelay", serviceConfig.getRetryDelay());
        config.put("asyncEnabled", serviceConfig.getAsyncEnabled());
        config.put("threadPoolSize", serviceConfig.getThreadPoolSize());
        config.put("batchProcessingEnabled", serviceConfig.getBatchProcessingEnabled());
        config.put("batchSize", serviceConfig.getBatchSize());
        config.put("rateLimitingEnabled", serviceConfig.getRateLimitingEnabled());
        config.put("rateLimitPerMinute", serviceConfig.getRateLimitPerMinute());
        config.put("circuitBreakerEnabled", serviceConfig.getCircuitBreakerEnabled());
        config.put("circuitBreakerThreshold", serviceConfig.getCircuitBreakerThreshold());
        config.put("circuitBreakerTimeout", serviceConfig.getCircuitBreakerTimeout());
        config.put("featureFlagsEnabled", serviceConfig.getFeatureFlagsEnabled());
        config.put("featureFlags", serviceConfig.getFeatureFlags());
        config.put("services", serviceConfig.getServices());
        
        return config;
    }
    
    /**
     * Check if a feature is enabled
     * 
     * @param featureName the feature name
     * @return true if feature is enabled
     */
    public boolean isFeatureEnabled(String featureName) {
        Boolean flagsEnabled = serviceConfig.getFeatureFlagsEnabled();
        if (flagsEnabled != null && !flagsEnabled) {
            return true; // All features enabled if feature flags disabled
        }

        Map<String, Boolean> featureFlags = serviceConfig.getFeatureFlags();
        if (featureFlags == null) {
            return true;
        }

        return featureFlags.getOrDefault(featureName, false);
    }
    
    /**
     * Check if a service is enabled
     * 
     * @param serviceName the service name
     * @return true if service is enabled
     */
    public boolean isServiceEnabled(String serviceName) {
        Map<String, AIServiceConfig.ServiceConfig> services = serviceConfig.getServices();
        if (services == null) {
            return false;
        }
        AIServiceConfig.ServiceConfig service = services.get(serviceName);
        return service != null && service.isEnabled();
    }
    
    /**
     * Get service configuration
     * 
     * @param serviceName the service name
     * @return service configuration
     */
    public AIServiceConfig.ServiceConfig getServiceConfig(String serviceName) {
        Map<String, AIServiceConfig.ServiceConfig> services = serviceConfig.getServices();
        if (services == null) {
            return null;
        }
        return services.get(serviceName);
    }
    
    /**
     * Get configuration summary
     * 
     * @return configuration summary
     */
    public Map<String, Object> getConfigurationSummary() {
        log.debug("Generating configuration summary");
        
        Map<String, Boolean> resolvedFeatureFlags = resolveFeatureFlags();
        Map<String, AIServiceConfig.ServiceConfig> services = serviceConfig.getServices();

        Map<String, Object> summary = new HashMap<>();
        summary.put("enabled", serviceConfig.getEnabled());
        summary.put("featuresEnabled", resolvedFeatureFlags.values().stream().mapToInt(b -> b ? 1 : 0).sum());
        summary.put("totalFeatures", resolvedFeatureFlags.size());
        summary.put("servicesEnabled", services != null
            ? services.values().stream().mapToInt(s -> s != null && s.isEnabled() ? 1 : 0).sum()
            : 0);
        summary.put("totalServices", services != null ? services.size() : 0);
        summary.put("cachingEnabled", serviceConfig.getCachingEnabled());
        summary.put("metricsEnabled", serviceConfig.getMetricsEnabled());
        summary.put("healthChecksEnabled", serviceConfig.getHealthChecksEnabled());
        summary.put("asyncEnabled", serviceConfig.getAsyncEnabled());
        summary.put("batchProcessingEnabled", serviceConfig.getBatchProcessingEnabled());
        summary.put("rateLimitingEnabled", serviceConfig.getRateLimitingEnabled());
        summary.put("circuitBreakerEnabled", serviceConfig.getCircuitBreakerEnabled());
        
        return summary;
    }

    private Map<String, Boolean> resolveFeatureFlags() {
        Map<String, Boolean> configured = serviceConfig.getFeatureFlags();
        if (configured != null && !configured.isEmpty()) {
            return new LinkedHashMap<>(configured);
        }

        AIServiceConfig.FeatureFlags features = serviceConfig.getFeatures();
        if (features == null) {
            return Map.of();
        }

        Map<String, Boolean> resolved = new LinkedHashMap<>();
        resolved.put("enableRAG", features.getEnableRAG());
        resolved.put("enableAdvancedRAG", features.getEnableAdvancedRAG());
        resolved.put("autoEnableAdvancedRAGForComplexQueries", features.getAutoEnableAdvancedRAGForComplexQueries());
        resolved.put("enableEmbeddings", features.getEnableEmbeddings());
        resolved.put("enableSearch", features.getEnableSearch());
        resolved.put("enableGeneration", features.getEnableGeneration());
        resolved.put("enableCaching", features.getEnableCaching());
        resolved.put("enableMonitoring", features.getEnableMonitoring());
        resolved.put("enableAnalytics", features.getEnableAnalytics());
        resolved.put("enableHealthChecks", features.getEnableHealthChecks());
        resolved.put("enableAutoScaling", features.getEnableAutoScaling());
        resolved.put("enableMultiProvider", features.getEnableMultiProvider());
        return resolved;
    }

    private Map<String, Object> buildProviderDetails() {
        Map<String, Object> details = new HashMap<>();
        details.put("llmDefaults", Map.of(
            "provider", providerConfig.getLlmProvider(),
            "model", providerConfig.resolveLlmDefaults().model(),
            "maxTokens", providerConfig.resolveLlmDefaults().maxTokens(),
            "temperature", providerConfig.resolveLlmDefaults().temperature(),
            "timeout", providerConfig.resolveLlmDefaults().timeoutSeconds()
        ));
        details.put("embeddingDefaults", Map.of(
            "provider", providerConfig.getEmbeddingProvider(),
            "model", providerConfig.resolveEmbeddingDefaults().model()
        ));
        details.put("openai", providerConfig.getOpenai());
        details.put("anthropic", providerConfig.getAnthropic());
        details.put("cohere", providerConfig.getCohere());
        details.put("onnx", providerConfig.getOnnx());
        details.put("pinecone", providerConfig.getPinecone());
        return details;
    }
}
