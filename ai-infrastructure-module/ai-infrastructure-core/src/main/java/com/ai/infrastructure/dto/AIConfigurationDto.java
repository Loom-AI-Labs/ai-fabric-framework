package com.ai.infrastructure.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * AI Configuration DTO
 * 
 * This DTO contains complete AI configuration including provider settings,
 * service settings, and feature flags.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIConfigurationDto {
    
    // Provider Configuration
    private String llmProvider;
    private String embeddingProvider;
    private Map<String, Object> providerDetails;
    private String openaiApiKey;
    private String openaiModel;
    private String openaiEmbeddingModel;
    private String pineconeApiKey;
    private String pineconeEnvironment;
    private String pineconeIndexName;
    
    // Service Configuration
    private boolean enabled;
    private boolean autoConfiguration;
    private boolean cachingEnabled;
    private boolean metricsEnabled;
    private boolean healthChecksEnabled;
    private boolean loggingEnabled;
    private long defaultTimeout;
    private int maxRetries;
    private long retryDelay;
    private boolean asyncEnabled;
    private int threadPoolSize;
    private boolean batchProcessingEnabled;
    private int batchSize;
    private boolean rateLimitingEnabled;
    private int rateLimitPerMinute;
    private boolean circuitBreakerEnabled;
    private int circuitBreakerThreshold;
    private long circuitBreakerTimeout;
    private boolean featureFlagsEnabled;
    private Map<String, Boolean> featureFlags;
    private Map<String, Object> services;
}
