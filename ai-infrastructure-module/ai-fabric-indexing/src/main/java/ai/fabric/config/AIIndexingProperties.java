package ai.fabric.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Configuration model for the indexing queue, workers, and cleanup jobs.
 */
@Data
@ConfigurationProperties(prefix = "ai.indexing")
public class AIIndexingProperties {

    private boolean enabled = true;
    private DocumentProperties documents = new DocumentProperties();
    private QueueProperties queue = new QueueProperties();
    private WorkerProperties syncRetryWorker = WorkerProperties.builder()
        .enabled(true)
        .fixedDelay(Duration.ofSeconds(2))
        .batchSize(25)
        .build();
    private WorkerProperties asyncWorker = WorkerProperties.builder()
        .enabled(true)
        .fixedDelay(Duration.ofMillis(1000))
        .batchSize(50)
        .build();
    private WorkerProperties batchWorker = WorkerProperties.builder()
        .enabled(true)
        .fixedDelay(Duration.ofSeconds(15))
        .batchSize(500)
        .build();
    private CleanupProperties cleanup = new CleanupProperties();

    @Data
    public static class DocumentProperties {
        private static final int PROTECTED_METADATA_ENTRY_COUNT = 10;

        private boolean enabled = true;
        private int maxDocumentsPerPlan = 100;
        private int maxChunksPerPlan = 500;
        private int maxContentLengthPerChunk = 10_000;
        private int maxTotalContentLength = 1_000_000;
        private int maxMetadataEntriesPerChunk = 32;
        private int maxMetadataValueLength = 512;
        private DefaultSplitterProperties defaultSplitter = new DefaultSplitterProperties();
        private MetadataProperties metadata = new MetadataProperties();

        public void validate() {
            requirePositive(maxDocumentsPerPlan, "max-documents-per-plan");
            requirePositive(maxChunksPerPlan, "max-chunks-per-plan");
            requirePositive(maxContentLengthPerChunk, "max-content-length-per-chunk");
            requirePositive(maxTotalContentLength, "max-total-content-length");
            if (maxTotalContentLength < maxContentLengthPerChunk) {
                throw new IllegalArgumentException(
                    "ai.indexing.documents.max-total-content-length must be greater than or equal to "
                        + "max-content-length-per-chunk"
                );
            }
            if (maxMetadataEntriesPerChunk < PROTECTED_METADATA_ENTRY_COUNT) {
                throw new IllegalArgumentException(
                    "ai.indexing.documents.max-metadata-entries-per-chunk must be at least "
                        + PROTECTED_METADATA_ENTRY_COUNT
                );
            }
            requirePositive(maxMetadataValueLength, "max-metadata-value-length");
            if (defaultSplitter == null) {
                throw new IllegalArgumentException(
                    "ai.indexing.documents.default-splitter is required"
                );
            }
            defaultSplitter.validate();
            if (metadata == null) {
                throw new IllegalArgumentException(
                    "ai.indexing.documents.metadata is required"
                );
            }
            metadata.normalize();
        }

        private void requirePositive(int value, String property) {
            if (value <= 0) {
                throw new IllegalArgumentException(
                    "ai.indexing.documents." + property + " must be positive"
                );
            }
        }
    }

    @Data
    public static class DefaultSplitterProperties {
        private boolean enabled = true;
        private int chunkSize = 800;
        private int minChunkSizeChars = 200;
        private int minChunkLengthToEmbed = 5;

        public void validate() {
            if (chunkSize <= 0) {
                throw new IllegalArgumentException(
                    "ai.indexing.documents.default-splitter.chunk-size must be positive"
                );
            }
            if (minChunkSizeChars < 0 || minChunkSizeChars > chunkSize) {
                throw new IllegalArgumentException(
                    "ai.indexing.documents.default-splitter.min-chunk-size-chars must be between 0 and chunk-size"
                );
            }
            if (minChunkLengthToEmbed < 0 || minChunkLengthToEmbed > chunkSize) {
                throw new IllegalArgumentException(
                    "ai.indexing.documents.default-splitter.min-chunk-length-to-embed must be between 0 and chunk-size"
                );
            }
        }
    }

    @Data
    public static class MetadataProperties {
        private Set<String> allowedApplicationKeys = new LinkedHashSet<>();
        private boolean warnOnDrop = true;

        private void normalize() {
            if (allowedApplicationKeys == null) {
                allowedApplicationKeys = new LinkedHashSet<>();
                return;
            }
            Set<String> normalized = new LinkedHashSet<>();
            for (String key : allowedApplicationKeys) {
                if (key != null && !key.isBlank()) {
                    normalized.add(key.trim());
                }
            }
            allowedApplicationKeys = normalized;
        }
    }

    @Data
    public static class QueueProperties {
        private int maxRetries = 5;
        private Duration visibilityTimeout = Duration.ofMinutes(2);
        private Duration syncCommitRecoveryTimeout = Duration.ofMinutes(10);
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

            public WorkerProperties build() {
                return target;
            }
        }
    }
}
