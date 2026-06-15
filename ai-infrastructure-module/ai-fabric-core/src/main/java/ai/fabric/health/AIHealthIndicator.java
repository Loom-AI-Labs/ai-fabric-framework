package ai.fabric.health;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.config.AIServiceConfig;
import ai.fabric.dto.AIHealthDto;
import ai.fabric.service.AIConfigurationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * AI Health Indicator
 * 
 * Spring Boot Actuator health indicator for AI infrastructure services.
 * Provides health status for AI services, providers, and overall system health.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class AIHealthIndicator {
    
    private final AIConfigurationService aiServiceConfigurationService;
    private final AIServiceConfig aiServiceConfig;
    private final AIProviderConfig aiProviderConfig;
    
    /**
     * Get health status as DTO
     */
    public AIHealthDto getHealthStatus() {
        Map<String, Object> summary = aiServiceConfigurationService.getConfigurationSummary();

        boolean enabled = aiServiceConfig != null && aiServiceConfig.isEnabled();
        boolean configurationValid = true; // validated at startup (fail-fast) when providers are enabled

        AIHealthDto.AIHealthDtoBuilder builder = AIHealthDto.builder()
            .enabled(enabled)
            .configurationValid(configurationValid)
            .providerConfigurationValid(configurationValid)
            .serviceConfigurationValid(configurationValid)
            .lastChecked(LocalDateTime.now().toString())
            .lastUpdated(LocalDateTime.now())
            .featuresEnabled(asInt(summary.get("featuresEnabled")))
            .totalFeatures(asInt(summary.get("totalFeatures")))
            .servicesEnabled(asInt(summary.get("servicesEnabled")))
            .totalServices(asInt(summary.get("totalServices")))
            .cachingEnabled(asBoolean(summary.get("cachingEnabled")))
            .metricsEnabled(asBoolean(summary.get("metricsEnabled")))
            .healthChecksEnabled(asBoolean(summary.get("healthChecksEnabled")))
            .asyncEnabled(asBoolean(summary.get("asyncEnabled")))
            .batchProcessingEnabled(asBoolean(summary.get("batchProcessingEnabled")))
            .rateLimitingEnabled(asBoolean(summary.get("rateLimitingEnabled")))
            .circuitBreakerEnabled(asBoolean(summary.get("circuitBreakerEnabled")))
            .openaiConfigured(hasText(aiProviderConfig != null ? aiProviderConfig.getOpenai().getApiKey() : null))
            .pineconeConfigured(hasText(aiProviderConfig != null ? aiProviderConfig.getPinecone().getApiKey() : null));

        AIHealthDto dto = builder.build();
        dto.setStatus(determineHealthStatus(dto));
        return dto;
    }
    
    /**
     * Get health status
     */
    public Map<String, Object> health() {
        try {
            log.debug("Checking AI infrastructure health...");
            
            // Check if AI services are enabled
            if (aiServiceConfig == null || !aiServiceConfig.isEnabled()) {
                return Map.of(
                    "status", "DOWN",
                    "reason", "AI services are disabled",
                    "timestamp", LocalDateTime.now()
                );
            }
            
            // Get comprehensive health information
            AIHealthDto healthInfo = getHealthStatus();
            
            // Determine overall health status
            String status = determineHealthStatus(healthInfo);
            
            // Build health response
            Map<String, Object> healthResponse = new HashMap<>();
            healthResponse.put("status", status);
            healthResponse.put("enabled", healthInfo.isEnabled());
            healthResponse.put("configurationValid", healthInfo.isConfigurationValid());
            healthResponse.put("featuresEnabled", healthInfo.getFeaturesEnabled());
            healthResponse.put("totalFeatures", healthInfo.getTotalFeatures());
            healthResponse.put("servicesEnabled", healthInfo.getServicesEnabled());
            healthResponse.put("totalServices", healthInfo.getTotalServices());
            healthResponse.put("llmProvider", aiProviderConfig != null ? aiProviderConfig.getLlmProvider() : null);
            healthResponse.put("embeddingProvider", aiProviderConfig != null ? aiProviderConfig.getEmbeddingProvider() : null);
            healthResponse.put("timestamp", LocalDateTime.now());
            
            // Add detailed information if available
            if (healthInfo.getPerformanceMetrics() != null) {
                healthResponse.put("performanceMetrics", healthInfo.getPerformanceMetrics());
            }
            
            if (healthInfo.getProviderStatus() != null) {
                healthResponse.put("providerStatus", healthInfo.getProviderStatus());
            }
            
            if (healthInfo.getServiceStatus() != null) {
                healthResponse.put("serviceStatus", healthInfo.getServiceStatus());
            }
            
            if (healthInfo.getSystemStatus() != null) {
                healthResponse.put("systemStatus", healthInfo.getSystemStatus());
            }
            
            if (healthInfo.getErrorMessage() != null) {
                healthResponse.put("errorMessage", healthInfo.getErrorMessage());
            }
            
            log.debug("AI infrastructure health check completed: {}", status);
            return healthResponse;
            
        } catch (Exception e) {
            log.error("Error checking AI infrastructure health", e);
            return Map.of(
                "status", "DOWN",
                "error", e.getMessage(),
                "timestamp", LocalDateTime.now()
            );
        }
    }
    
    /**
     * Determine overall health status
     */
    private String determineHealthStatus(AIHealthDto healthInfo) {
        if (!healthInfo.isEnabled()) {
            return "DOWN";
        }
        
        if (!healthInfo.isConfigurationValid()) {
            return "DOWN";
        }
        
        if (healthInfo.getErrorMessage() != null && !healthInfo.getErrorMessage().trim().isEmpty()) {
            return "DOWN";
        }
        
        if (healthInfo.getFeaturesEnabled() == 0) {
            return "DOWN";
        }
        
        if (healthInfo.getServicesEnabled() == 0) {
            return "DOWN";
        }
        
        return "UP";
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return false;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
    
    /**
     * Get health summary
     */
    public Map<String, Object> getHealthSummary() {
        try {
            AIHealthDto healthInfo = getHealthStatus();
            
            return Map.of(
                "enabled", healthInfo.isEnabled(),
                "status", healthInfo.getStatus(),
                "featuresEnabled", healthInfo.getFeaturesEnabled(),
                "totalFeatures", healthInfo.getTotalFeatures(),
                "servicesEnabled", healthInfo.getServicesEnabled(),
                "totalServices", healthInfo.getTotalServices(),
                "lastUpdated", healthInfo.getLastUpdated(),
                "configurationValid", healthInfo.isConfigurationValid(),
                "providerConfigurationValid", healthInfo.isProviderConfigurationValid(),
                "serviceConfigurationValid", healthInfo.isServiceConfigurationValid()
            );
        } catch (Exception e) {
            log.error("Error getting health summary", e);
            return Map.of(
                "enabled", false,
                "status", "ERROR",
                "error", e.getMessage(),
                "timestamp", LocalDateTime.now()
            );
        }
    }
    
    /**
     * Check if AI services are healthy
     */
    public boolean isHealthy() {
        try {
            Map<String, Object> health = health();
            return "UP".equals(health.get("status"));
        } catch (Exception e) {
            log.error("Error checking health status", e);
            return false;
        }
    }
    
    /**
     * Get detailed health information
     */
    public Map<String, Object> getDetailedHealth() {
        return health();
    }
}