package ai.fabric.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Map;

/**
 * AI Service Configuration
 * 
 * Centralized configuration for AI services including provider settings,
 * performance tuning, and feature flags.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
// @Component
@ConfigurationProperties(prefix = "ai.service")
public class AIServiceConfig {
    
    /**
     * Whether AI services are enabled
     */
    @Builder.Default
    private Boolean enabled = true;
    
    /**
     * Whether auto-configuration is enabled
     */
    @Builder.Default
    private Boolean autoConfiguration = true;
    
    /**
     * Whether caching is enabled
     */
    @Builder.Default
    private Boolean cachingEnabled = true;
    
    /**
     * Whether metrics are enabled
     */
    @Builder.Default
    private Boolean metricsEnabled = true;
    
    /**
     * Whether health checks are enabled
     */
    @Builder.Default
    private Boolean healthChecksEnabled = true;
    
    /**
     * Whether logging is enabled
     */
    @Builder.Default
    private Boolean loggingEnabled = true;
    
    /**
     * Default timeout in seconds
     */
    @Builder.Default
    private Integer defaultTimeout = 30;
    
    /**
     * Maximum retries
     */
    @Builder.Default
    private Integer maxRetries = 3;
    
    /**
     * Retry delay in milliseconds
     */
    @Builder.Default
    private Long retryDelay = 1000L;
    
    /**
     * Whether async processing is enabled
     */
    @Builder.Default
    private Boolean asyncEnabled = true;
    
    /**
     * Thread pool size
     */
    @Builder.Default
    private Integer threadPoolSize = 10;
    
    /**
     * Whether batch processing is enabled
     */
    @Builder.Default
    private Boolean batchProcessingEnabled = false;
    
    /**
     * Batch size
     */
    @Builder.Default
    private Integer batchSize = 100;
    
    /**
     * Whether rate limiting is enabled
     */
    @Builder.Default
    private Boolean rateLimitingEnabled = true;
    
    /**
     * Rate limit per minute
     */
    @Builder.Default
    private Integer rateLimitPerMinute = 60;
    
    /**
     * Whether circuit breaker is enabled
     */
    @Builder.Default
    private Boolean circuitBreakerEnabled = true;
    
    /**
     * Circuit breaker threshold
     */
    @Builder.Default
    private Integer circuitBreakerThreshold = 5;
    
    /**
     * Circuit breaker timeout in seconds
     */
    @Builder.Default
    private Integer circuitBreakerTimeout = 30;
    
    /**
     * Whether feature flags are enabled
     */
    @Builder.Default
    private Boolean featureFlagsEnabled = true;
    
    /**
     * Feature flags
     */
    private Map<String, Boolean> featureFlags;
    
    /**
     * Service configurations
     */
    private Map<String, ServiceConfig> services;
    
    /**
     * Default AI provider
     */
    @Builder.Default
    private String defaultProvider = "openai";
    
    /**
     * Fallback AI provider
     */
    @Builder.Default
    private String fallbackProvider = "anthropic";
    
    /**
     * Request timeout configuration
     */
    private TimeoutConfig timeout;
    
    /**
     * Retry configuration
     */
    private RetryConfig retry;
    
    /**
     * Rate limiting configuration
     */
    private RateLimitConfig rateLimit;
    
    /**
     * Caching configuration
     */
    private CacheConfig cache;
    
    /**
     * Performance configuration
     */
    private PerformanceConfig performance;
    
    /**
     * Feature flags
     */
    @Builder.Default
    private FeatureFlags features = new FeatureFlags();
    
    /**
     * Provider-specific configurations
     */
    private Map<String, ProviderConfig> providers;
    
    /**
     * Model configurations
     */
    private Map<String, ModelConfig> models;
    
    /**
     * Security configuration
     */
    private SecurityConfig security;
    
    /**
     * Monitoring configuration
     */
    private MonitoringConfig monitoring;
    
    /**
     * Timeout Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimeoutConfig {
        @Builder.Default
        private Duration defaultTimeout = Duration.ofSeconds(30);
        
        @Builder.Default
        private Duration embeddingTimeout = Duration.ofSeconds(15);
        
        @Builder.Default
        private Duration generationTimeout = Duration.ofSeconds(60);
        
        @Builder.Default
        private Duration searchTimeout = Duration.ofSeconds(10);
        
        @Builder.Default
        private Duration ragTimeout = Duration.ofSeconds(45);
    }
    
    /**
     * Retry Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetryConfig {
        @Builder.Default
        private Integer maxAttempts = 3;
        
        @Builder.Default
        private Duration initialDelay = Duration.ofMillis(1000);
        
        @Builder.Default
        private Double backoffMultiplier = 2.0;
        
        @Builder.Default
        private Duration maxDelay = Duration.ofSeconds(30);
        
        @Builder.Default
        private List<Integer> retryableStatusCodes = List.of(429, 500, 502, 503, 504);
    }
    
    /**
     * Rate Limit Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RateLimitConfig {
        @Builder.Default
        private Integer requestsPerMinute = 60;
        
        @Builder.Default
        private Integer requestsPerHour = 1000;
        
        @Builder.Default
        private Integer requestsPerDay = 10000;
        
        @Builder.Default
        private Boolean enabled = true;
        
        @Builder.Default
        private String strategy = "sliding_window";
    }
    
    /**
     * Cache Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CacheConfig {
        @Builder.Default
        private Boolean enabled = true;
        
        @Builder.Default
        private Duration defaultTtl = Duration.ofHours(1);
        
        @Builder.Default
        private Long maxSize = 10000L;
        
        @Builder.Default
        private String evictionPolicy = "LRU";
        
        @Builder.Default
        private Boolean enableMetrics = true;
    }
    
    /**
     * Performance Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerformanceConfig {
        @Builder.Default
        private Integer maxConcurrentRequests = 10;
        
        @Builder.Default
        private Integer threadPoolSize = 20;
        
        @Builder.Default
        private Boolean enableAsyncProcessing = true;
        
        @Builder.Default
        private Boolean enableBatching = true;
        
        @Builder.Default
        private Integer batchSize = 10;
        
        @Builder.Default
        private Duration batchTimeout = Duration.ofMillis(100);
    }
    
    /**
     * Feature Flags
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeatureFlags {
        @Builder.Default
        private Boolean enableRAG = true;

        /**
         * Enable Advanced RAG orchestration integration (query expansion, re-ranking, context optimization).
         */
        @Builder.Default
        private Boolean enableAdvancedRAG = true;

        /**
         * Auto-enable Advanced RAG for complex queries when an AdvancedRAGProvider is present.
         */
        @Builder.Default
        private Boolean autoEnableAdvancedRAGForComplexQueries = false;
        
        @Builder.Default
        private Boolean enableEmbeddings = true;
        
        @Builder.Default
        private Boolean enableSearch = true;
        
        @Builder.Default
        private Boolean enableGeneration = true;
        
        @Builder.Default
        private Boolean enableCaching = true;
        
        @Builder.Default
        private Boolean enableMonitoring = true;
        
        @Builder.Default
        private Boolean enableAnalytics = true;
        
        @Builder.Default
        private Boolean enableHealthChecks = true;
        
        @Builder.Default
        private Boolean enableAutoScaling = false;
        
        @Builder.Default
        private Boolean enableMultiProvider = true;
    }
    
    /**
     * Provider Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProviderConfig {
        private String name;
        private String apiKey;
        private String baseUrl;
        private String defaultModel;
        private Integer priority;
        private Boolean enabled;
        private Map<String, Object> parameters;
    }
    
    /**
     * Model Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelConfig {
        private String name;
        private String provider;
        private String type;
        private Integer maxTokens;
        private Double temperature;
        private Double topP;
        private Integer topK;
        private Boolean enabled;
        private Map<String, Object> parameters;
    }
    
    /**
     * Security Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SecurityConfig {
        @Builder.Default
        private Boolean enableEncryption = true;
        
        @Builder.Default
        private Boolean enableAuditLogging = true;
        
        @Builder.Default
        private Boolean enableDataMasking = false;
        
        @Builder.Default
        private List<String> allowedOrigins = List.of("*");
        
        @Builder.Default
        private Boolean requireAuthentication = false;
        
        @Builder.Default
        private String encryptionAlgorithm = "AES-256-GCM";
    }
    
    /**
     * Monitoring Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonitoringConfig {
        @Builder.Default
        private Boolean enableMetrics = true;
        
        @Builder.Default
        private Boolean enableTracing = true;
        
        @Builder.Default
        private Boolean enableLogging = true;
        
        @Builder.Default
        private String metricsPrefix = "ai.service";
        
        @Builder.Default
        private Duration metricsInterval = Duration.ofSeconds(30);
        
        @Builder.Default
        private Boolean enableHealthChecks = true;
        
        @Builder.Default
        private Duration healthCheckInterval = Duration.ofMinutes(1);
    }
    
    // Alias methods for boolean getters
    public boolean isEnabled() { return enabled != null && enabled; }
    public boolean isAutoConfiguration() { return autoConfiguration != null && autoConfiguration; }
    public boolean isCachingEnabled() { return cachingEnabled != null && cachingEnabled; }
    public boolean isMetricsEnabled() { return metricsEnabled != null && metricsEnabled; }
    public boolean isHealthChecksEnabled() { return healthChecksEnabled != null && healthChecksEnabled; }
    public boolean isLoggingEnabled() { return loggingEnabled != null && loggingEnabled; }
    public boolean isAsyncEnabled() { return asyncEnabled != null && asyncEnabled; }
    public boolean isBatchProcessingEnabled() { return batchProcessingEnabled != null && batchProcessingEnabled; }
    public boolean isRateLimitingEnabled() { return rateLimitingEnabled != null && rateLimitingEnabled; }
    public boolean isCircuitBreakerEnabled() { return circuitBreakerEnabled != null && circuitBreakerEnabled; }
    
    /**
     * Service Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServiceConfig {
        private String name;
        private Boolean enabled;
        private String provider;
        private Map<String, Object> configuration;
        private TimeoutConfig timeout;
        private RetryConfig retry;
        private RateLimitConfig rateLimit;
        private Map<String, Object> metadata;
        
        public boolean isEnabled() {
            return enabled != null && enabled;
        }
    }
}