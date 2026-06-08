package com.ai.infrastructure.behavior.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration properties for behavior analysis processing and scheduling.
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.behavior.processing")
public class BehaviorProcessingProperties {

    // Scheduled processing
    private boolean scheduledEnabled = false;
    private String scheduleCron = "0 */15 * * * *";
    private int scheduledBatchSize = 100;
    private Duration scheduledMaxDuration = Duration.ofMinutes(10);

    // API-triggered processing
    private boolean apiEnabled = true;
    private int apiMaxBatchSize = 1000;
    private Duration apiMaxDuration = Duration.ofMinutes(30);

    // Throttling
    private Duration processingDelay = Duration.ofMillis(100);

    // Continuous processing defaults
    private int continuousUsersPerBatch = 100;
    private Duration continuousInterval = Duration.ofMinutes(5);
}
