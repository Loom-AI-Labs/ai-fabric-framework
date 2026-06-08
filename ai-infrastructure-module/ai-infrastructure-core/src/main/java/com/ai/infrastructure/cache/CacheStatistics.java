package com.ai.infrastructure.cache;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Cache Statistics DTO
 * 
 * Represents comprehensive statistics and metrics for the AI intelligent cache system.
 * Provides insights into cache performance, usage patterns, and optimization opportunities.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CacheStatistics {
    
    /**
     * Total number of cache hits
     */
    @Builder.Default
    private long hitCount = 0L;
    
    /**
     * Total number of cache misses
     */
    @Builder.Default
    private long missCount = 0L;
    
    /**
     * Cache hit rate as percentage (0.0 to 100.0)
     */
    @Builder.Default
    private double hitRate = 0.0;
    
    /**
     * Cache miss rate as percentage (0.0 to 100.0)
     */
    @Builder.Default
    private double missRate = 0.0;
    
    /**
     * Total number of cache requests
     */
    @Builder.Default
    private long requestCount = 0L;
    
    /**
     * Total number of cache entries
     */
    @Builder.Default
    private long totalSize = 0L;
    
    /**
     * Maximum cache size
     */
    @Builder.Default
    private long maxSize = 0L;
    
    /**
     * Cache memory usage in bytes
     */
    @Builder.Default
    private long memoryUsage = 0L;
    
    /**
     * Maximum memory usage in bytes
     */
    @Builder.Default
    private long maxMemoryUsage = 0L;
    
    /**
     * Memory usage percentage (0.0 to 100.0)
     */
    @Builder.Default
    private double memoryUsagePercentage = 0.0;
    
    /**
     * Total number of evictions
     */
    @Builder.Default
    private long evictionCount = 0L;
    
    /**
     * Total number of expirations
     */
    @Builder.Default
    private long expirationCount = 0L;
    
    /**
     * Total number of loads
     */
    @Builder.Default
    private long loadCount = 0L;
    
    /**
     * Total load time in milliseconds
     */
    @Builder.Default
    private long loadTime = 0L;
    
    /**
     * Average load time in milliseconds
     */
    @Builder.Default
    private double averageLoadTime = 0.0;
    
    /**
     * Total response time in milliseconds
     */
    @Builder.Default
    private long totalResponseTime = 0L;
    
    /**
     * Average response time in milliseconds
     */
    @Builder.Default
    private double averageResponseTime = 0.0;
    
    /**
     * Minimum response time in milliseconds
     */
    @Builder.Default
    private long minResponseTime = Long.MAX_VALUE;
    
    /**
     * Maximum response time in milliseconds
     */
    @Builder.Default
    private long maxResponseTime = 0L;
    
    /**
     * Cache efficiency score (0.0 to 1.0)
     */
    @Builder.Default
    private double efficiencyScore = 0.0;
    
    /**
     * Cache performance score (0.0 to 1.0)
     */
    @Builder.Default
    private double performanceScore = 0.0;
    
    /**
     * Cache health score (0.0 to 1.0)
     */
    @Builder.Default
    private double healthScore = 0.0;
    
    /**
     * Cache utilization percentage (0.0 to 100.0)
     */
    @Builder.Default
    private double utilizationPercentage = 0.0;
    
    /**
     * Cache fragmentation percentage (0.0 to 100.0)
     */
    @Builder.Default
    private double fragmentationPercentage = 0.0;
    
    /**
     * Cache compression ratio
     */
    @Builder.Default
    private double compressionRatio = 1.0;
    
    /**
     * Cache deduplication ratio
     */
    @Builder.Default
    private double deduplicationRatio = 1.0;
    
    /**
     * Cache optimization ratio
     */
    @Builder.Default
    private double optimizationRatio = 1.0;
    
    /**
     * Top keys by usage count
     */
    private List<String> topKeys;
    
    /**
     * Top keys by memory usage
     */
    private List<String> topKeysByMemory;
    
    /**
     * Top keys by access frequency
     */
    private List<String> topKeysByFrequency;
    
    /**
     * Top keys by hit rate
     */
    private List<String> topKeysByHitRate;
    
    /**
     * Top keys by miss rate
     */
    private List<String> topKeysByMissRate;
    
    /**
     * Top keys by response time
     */
    private List<String> topKeysByResponseTime;
    
    /**
     * Top keys by load time
     */
    private List<String> topKeysByLoadTime;
    
    /**
     * Top keys by eviction count
     */
    private List<String> topKeysByEviction;
    
    /**
     * Top keys by expiration count
     */
    private List<String> topKeysByExpiration;
    
    /**
     * Top keys by load count
     */
    private List<String> topKeysByLoad;
    
    /**
     * Memory usage breakdown by key
     */
    private Map<String, Long> memoryUsageByKey;
    
    /**
     * Hit count breakdown by key
     */
    private Map<String, Long> hitCountByKey;
    
    /**
     * Miss count breakdown by key
     */
    private Map<String, Long> missCountByKey;
    
    /**
     * Response time breakdown by key
     */
    private Map<String, Double> responseTimeByKey;
    
    /**
     * Load time breakdown by key
     */
    private Map<String, Double> loadTimeByKey;
    
    /**
     * Eviction count breakdown by key
     */
    private Map<String, Long> evictionCountByKey;
    
    /**
     * Expiration count breakdown by key
     */
    private Map<String, Long> expirationCountByKey;
    
    /**
     * Load count breakdown by key
     */
    private Map<String, Long> loadCountByKey;
    
    /**
     * Hit rate breakdown by key
     */
    private Map<String, Double> hitRateByKey;
    
    /**
     * Miss rate breakdown by key
     */
    private Map<String, Double> missRateByKey;
    
    /**
     * Efficiency score breakdown by key
     */
    private Map<String, Double> efficiencyScoreByKey;
    
    /**
     * Performance score breakdown by key
     */
    private Map<String, Double> performanceScoreByKey;
    
    /**
     * Health score breakdown by key
     */
    private Map<String, Double> healthScoreByKey;
    
    /**
     * Utilization percentage breakdown by key
     */
    private Map<String, Double> utilizationPercentageByKey;
    
    /**
     * Fragmentation percentage breakdown by key
     */
    private Map<String, Double> fragmentationPercentageByKey;
    
    /**
     * Compression ratio breakdown by key
     */
    private Map<String, Double> compressionRatioByKey;
    
    /**
     * Deduplication ratio breakdown by key
     */
    private Map<String, Double> deduplicationRatioByKey;
    
    /**
     * Optimization ratio breakdown by key
     */
    private Map<String, Double> optimizationRatioByKey;
    
    /**
     * Cache configuration
     */
    private Map<String, Object> configuration;
    
    /**
     * Cache environment
     */
    private Map<String, String> environment;
    
    /**
     * Cache metadata
     */
    private Map<String, Object> metadata;
    
    /**
     * Cache tags
     */
    private List<String> tags;
    
    /**
     * Cache categories
     */
    private List<String> categories;
    
    /**
     * Cache namespaces
     */
    private List<String> namespaces;
    
    /**
     * Cache groups
     */
    private List<String> groups;
    
    /**
     * Cache owners
     */
    private List<String> owners;
    
    /**
     * Cache maintainers
     */
    private List<String> maintainers;
    
    /**
     * Cache versions
     */
    private List<String> versions;
    
    /**
     * Cache priorities
     */
    private List<Integer> priorities;
    
    /**
     * Cache weights
     */
    private List<Double> weights;
    
    /**
     * Cache status
     */
    private String status;
    
    /**
     * Cache health status
     */
    private String healthStatus;
    
    /**
     * Cache performance status
     */
    private String performanceStatus;
    
    /**
     * Cache efficiency status
     */
    private String efficiencyStatus;
    
    /**
     * Cache utilization status
     */
    private String utilizationStatus;
    
    /**
     * Cache fragmentation status
     */
    private String fragmentationStatus;
    
    /**
     * Cache compression status
     */
    private String compressionStatus;
    
    /**
     * Cache deduplication status
     */
    private String deduplicationStatus;
    
    /**
     * Cache optimization status
     */
    private String optimizationStatus;
    
    /**
     * Cache monitoring status
     */
    private String monitoringStatus;
    
    /**
     * Cache alerting status
     */
    private String alertingStatus;
    
    /**
     * Cache backup status
     */
    private String backupStatus;
    
    /**
     * Cache recovery status
     */
    private String recoveryStatus;
    
    /**
     * Cache scaling status
     */
    private String scalingStatus;
    
    /**
     * Cache security status
     */
    private String securityStatus;
    
    /**
     * Cache compliance status
     */
    private String complianceStatus;
    
    /**
     * Cache governance status
     */
    private String governanceStatus;
    
    /**
     * Cache lifecycle status
     */
    private String lifecycleStatus;
    
    /**
     * Cache maintenance status
     */
    private String maintenanceStatus;
    
    /**
     * Cache support status
     */
    private String supportStatus;
    
    /**
     * Cache training status
     */
    private String trainingStatus;
    
    /**
     * Cache documentation status
     */
    private String documentationStatus;
    
    /**
     * Cache communication status
     */
    private String communicationStatus;
    
    /**
     * Cache stakeholder status
     */
    private String stakeholderStatus;
    
    /**
     * Cache recommendations
     */
    private List<String> recommendations;
    
    /**
     * Cache warnings
     */
    private List<String> warnings;
    
    /**
     * Cache errors
     */
    private List<String> errors;
    
    /**
     * Cache alerts
     */
    private List<String> alerts;
    
    /**
     * Cache notifications
     */
    private List<String> notifications;
    
    /**
     * Cache reports
     */
    private List<String> reports;
    
    /**
     * Cache dashboards
     */
    private List<String> dashboards;
    
    /**
     * Cache metrics
     */
    private List<String> metrics;
    
    /**
     * Cache logs
     */
    private List<String> logs;
    
    /**
     * Cache traces
     */
    private List<String> traces;
    
    /**
     * Cache profiles
     */
    private List<String> profiles;
    
    /**
     * Cache debug information
     */
    private List<String> debugInfo;
    
    /**
     * Cache test results
     */
    private List<String> testResults;
    
    /**
     * Cache validation results
     */
    private List<String> validationResults;
    
    /**
     * Cache sanitization results
     */
    private List<String> sanitizationResults;
    
    /**
     * Cache audit results
     */
    private List<String> auditResults;
    
    /**
     * Cache compliance results
     */
    private List<String> complianceResults;
    
    /**
     * Cache governance results
     */
    private List<String> governanceResults;
    
    /**
     * Cache lifecycle results
     */
    private List<String> lifecycleResults;
    
    /**
     * Cache maintenance results
     */
    private List<String> maintenanceResults;
    
    /**
     * Cache support results
     */
    private List<String> supportResults;
    
    /**
     * Cache training results
     */
    private List<String> trainingResults;
    
    /**
     * Cache documentation results
     */
    private List<String> documentationResults;
    
    /**
     * Cache communication results
     */
    private List<String> communicationResults;
    
    /**
     * Cache stakeholder results
     */
    private List<String> stakeholderResults;
    
    /**
     * Cache success criteria
     */
    private List<String> successCriteria;
    
    /**
     * Cache acceptance criteria
     */
    private List<String> acceptanceCriteria;
    
    /**
     * Cache quality criteria
     */
    private List<String> qualityCriteria;
    
    /**
     * Cache performance criteria
     */
    private List<String> performanceCriteria;
    
    /**
     * Cache security criteria
     */
    private List<String> securityCriteria;
    
    /**
     * Cache compliance criteria
     */
    private List<String> complianceCriteria;
    
    /**
     * Cache governance criteria
     */
    private List<String> governanceCriteria;
    
    /**
     * Cache lifecycle criteria
     */
    private List<String> lifecycleCriteria;
    
    /**
     * Cache maintenance criteria
     */
    private List<String> maintenanceCriteria;
    
    /**
     * Cache support criteria
     */
    private List<String> supportCriteria;
    
    /**
     * Cache training criteria
     */
    private List<String> trainingCriteria;
    
    /**
     * Cache documentation criteria
     */
    private List<String> documentationCriteria;
    
    /**
     * Cache communication criteria
     */
    private List<String> communicationCriteria;
    
    /**
     * Cache stakeholder criteria
     */
    private List<String> stakeholderCriteria;
    
    /**
     * Timestamp when statistics were generated
     */
    private LocalDateTime generatedAt;
    
    /**
     * Timestamp when statistics were last updated
     */
    private LocalDateTime lastUpdated;
    
    /**
     * Statistics generation source
     */
    private String generatedBy;
    
    /**
     * Statistics generation version
     */
    private String generatorVersion;
    
    /**
     * Whether statistics are valid
     */
    private Boolean valid;
    
    /**
     * Validation errors if any
     */
    private List<String> validationErrors;
    
    /**
     * Statistics summary
     */
    private Map<String, Object> summary;
    
    /**
     * Statistics details
     */
    private Map<String, Object> details;
    
    /**
     * Statistics metadata
     */
    private Map<String, Object> statisticsMetadata;
}