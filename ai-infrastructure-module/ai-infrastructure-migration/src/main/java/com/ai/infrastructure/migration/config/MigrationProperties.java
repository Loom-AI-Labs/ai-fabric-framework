package com.ai.infrastructure.migration.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "ai.migration")
public class MigrationProperties {
    /**
     * Master switch for migration features.
     */
    private boolean enabled = true;

    /**
     * Default batch size when none is provided.
     */
    private int defaultBatchSize = 500;

    /**
     * Default max requests per minute when rate limiting.
     * Null or zero disables rate limiting.
     */
    private Integer defaultRateLimit = 100;

    /**
     * Maximum concurrent jobs processed by the executor.
     */
    private int maxConcurrentJobs = 3;

    /**
     * Days to retain completed job records.
     */
    private int cleanupCompletedAfterDays = 30;

    /**
     * Per-entity field mapping used by the default migration filter when no custom policy is provided.
     * Key: entityType, Value: field configuration.
     */
    private Map<String, MigrationFieldConfig> entityFields = new HashMap<>();
}
