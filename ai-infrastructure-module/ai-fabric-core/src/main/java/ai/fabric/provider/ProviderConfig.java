package ai.fabric.provider;

import lombok.Builder;
import lombok.Data;

/**
 * Provider Configuration
 * 
 * Configuration settings for AI providers including API keys,
 * models, timeouts, and other provider-specific settings.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Data
@Builder
public class ProviderConfig {
    
    /**
     * Provider name
     */
    private String providerName;
    
    /**
     * API key for the provider
     */
    private String apiKey;
    
    /**
     * Base URL for the provider API
     */
    private String baseUrl;
    
    /**
     * Default model for content generation
     */
    private String defaultModel;
    
    /**
     * Default model for embeddings
     */
    private String defaultEmbeddingModel;

    /**
     * Optional embedding-specific API key.
     */
    private String embeddingApiKey;

    /**
     * Optional embedding-specific base URL.
     */
    private String embeddingBaseUrl;
    
    /**
     * Maximum tokens for generation
     */
    private Integer maxTokens;
    
    /**
     * Default temperature for generation
     */
    private Double temperature;
    
    /**
     * Request timeout in seconds
     */
    private Integer timeoutSeconds;
    
    /**
     * Maximum retries for failed requests
     */
    private Integer maxRetries;
    
    /**
     * Retry delay in milliseconds
     */
    private Long retryDelayMs;
    
    /**
     * Rate limit per minute
     */
    private Integer rateLimitPerMinute;
    
    /**
     * Rate limit per day
     */
    private Integer rateLimitPerDay;
    
    /**
     * Whether the provider is enabled
     */
    private boolean enabled;
    
    /**
     * Priority for provider selection (higher = more preferred)
     */
    private Integer priority;
    
    /**
     * Provider-specific configuration
     */
    private java.util.Map<String, Object> customConfig;
    
    /**
     * Check if configuration is valid
     * 
     * @return true if valid
     */
    public boolean isValid() {
        return providerName != null && !providerName.trim().isEmpty() &&
               apiKey != null && !apiKey.trim().isEmpty() &&
               baseUrl != null && !baseUrl.trim().isEmpty() &&
               defaultModel != null && !defaultModel.trim().isEmpty() &&
               timeoutSeconds != null && timeoutSeconds > 0 &&
               enabled;
    }
    
    /**
     * Get custom configuration value
     * 
     * @param key configuration key
     * @return configuration value
     */
    public Object getCustomConfig(String key) {
        return customConfig != null ? customConfig.get(key) : null;
    }
    
    /**
     * Set custom configuration value
     * 
     * @param key configuration key
     * @param value configuration value
     */
    public void setCustomConfig(String key, Object value) {
        if (customConfig == null) {
            customConfig = new java.util.HashMap<>();
        }
        customConfig.put(key, value);
    }
}
