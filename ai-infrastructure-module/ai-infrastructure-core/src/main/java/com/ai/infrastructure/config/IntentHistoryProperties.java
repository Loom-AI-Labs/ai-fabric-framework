package com.ai.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration options for the intent history layer.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai.intent-history")
public class IntentHistoryProperties {

    /**
     * Master switch that enables the persistence layer.
     */
    private boolean enabled = true;

    /**
     * Number of days that an intent history record is retained before eligible for cleanup.
     */
    private int retentionDays = 90;

    /**
     * Cron expression controlling the cleanup schedule. Defaults to hourly cleanup.
     */
    private String cleanupCron = "0 0 * * * *";

    /**
     * When true, the original query is encrypted (if available) before persisting.
     */
    private boolean storeEncryptedQuery = true;
}
