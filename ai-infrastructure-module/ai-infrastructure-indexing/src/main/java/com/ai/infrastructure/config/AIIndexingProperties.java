package com.ai.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration model for the indexing queue, workers, and cleanup jobs.
 */
@Data
@ConfigurationProperties(prefix = "ai.indexing")
public class AIIndexingProperties {

    private boolean enabled = true;
    private QueueProperties queue = new QueueProperties();
    private WorkerProperties asyncWorker = WorkerProperties.builder()
        .enabled(true)
        .fixedDelay(Duration.ofMillis(1000))
        .batchSize(50)
        .strategy("ASYNC")
        .build();
    private WorkerProperties batchWorker = WorkerProperties.builder()
        .enabled(true)
        .fixedDelay(Duration.ofSeconds(15))
        .batchSize(500)
        .strategy("BATCH")
        .build();
    private CleanupProperties cleanup = new CleanupProperties();

    @Data
    public static class QueueProperties {
        private int maxRetries = 5;
        private Duration visibilityTimeout = Duration.ofMinutes(2);
    }

    @Data
    public static class CleanupProperties {
        private boolean enabled = true;
        private Duration stuckThreshold = Duration.ofMinutes(10);
        private Duration sweepInterval = Duration.ofMinutes(5);
        private Duration completedRetention = Duration.ofDays(7);
        private Duration deadLetterRetention = Duration.ofDays(30);
    }

    @Data
    public static class WorkerProperties {
        private boolean enabled = true;
        private Duration fixedDelay = Duration.ofSeconds(1);
        private int batchSize = 50;
        private String strategy = "ASYNC";

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private final WorkerProperties target = new WorkerProperties();

            public Builder enabled(boolean enabled) {
                target.setEnabled(enabled);
                return this;
            }

            public Builder fixedDelay(Duration delay) {
                target.setFixedDelay(delay);
                return this;
            }

            public Builder batchSize(int size) {
                target.setBatchSize(size);
                return this;
            }

            public Builder strategy(String strategy) {
                target.setStrategy(strategy);
                return this;
            }

            public WorkerProperties build() {
                return target;
            }
        }
    }
}
