package com.ai.infrastructure.provider;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Provider Status
 * 
 * Represents the current status of an AI provider including
 * availability, performance metrics, and health information.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Data
@Builder
public class ProviderStatus {
    
    /**
     * Provider name
     */
    private String providerName;
    
    /**
     * Whether the provider is available
     */
    private boolean available;
    
    /**
     * Whether the provider is healthy
     */
    private boolean healthy;
    
    /**
     * Last successful request timestamp
     */
    private LocalDateTime lastSuccess;
    
    /**
     * Last error timestamp
     */
    private LocalDateTime lastError;
    
    /**
     * Last error message
     */
    private String lastErrorMessage;
    
    /**
     * Total requests made
     */
    private long totalRequests;
    
    /**
     * Successful requests
     */
    private long successfulRequests;
    
    /**
     * Failed requests
     */
    private long failedRequests;
    
    /**
     * Average response time in milliseconds
     */
    private double averageResponseTime;
    
    /**
     * Success rate (0.0 to 1.0)
     */
    private double successRate;
    
    /**
     * Current rate limit remaining
     */
    private int rateLimitRemaining;
    
    /**
     * Rate limit reset time
     */
    private LocalDateTime rateLimitReset;
    
    /**
     * Provider-specific status details
     */
    private String details;
    
    /**
     * Last status update timestamp
     */
    private LocalDateTime lastUpdated;
    
    /**
     * Check if provider is operational
     * 
     * @return true if operational
     */
    public boolean isOperational() {
        return available && healthy && successRate > 0.8;
    }
    
    /**
     * Check if provider has recent activity
     * 
     * @param minutes minutes to check
     * @return true if has recent activity
     */
    public boolean hasRecentActivity(int minutes) {
        if (lastSuccess == null) {
            return false;
        }
        return lastSuccess.isAfter(LocalDateTime.now().minusMinutes(minutes));
    }
    
    /**
     * Get error rate
     * 
     * @return error rate (0.0 to 1.0)
     */
    public double getErrorRate() {
        if (totalRequests == 0) {
            return 0.0;
        }
        return (double) failedRequests / totalRequests;
    }
}