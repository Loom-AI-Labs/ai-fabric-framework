package ai.fabric.indexing.document.springai;

import ai.fabric.indexing.IndexingActionPlan;
import ai.fabric.indexing.IndexingOperation;
import ai.fabric.indexing.api.IndexingStrategy;
import org.springframework.ai.document.DocumentTransformer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Options for converting trusted Spring AI documents into AI Fabric indexing work.
 */
public record SpringAiDocumentIndexingOptions(
    String entityType,
    String sourceId,
    String sourceName,
    IndexingOperation operation,
    IndexingStrategy strategy,
    IndexingActionPlan actionPlan,
    List<DocumentTransformer> transformers,
    boolean splitWithTokenTextSplitter,
    int tokenChunkSize,
    int maxChunks,
    int maxContentLength,
    int maxMetadataEntries,
    int maxMetadataValueLength,
    Map<String, Object> metadata,
    LocalDateTime scheduledFor,
    int maxRetries
) {

    private static final int DEFAULT_TOKEN_CHUNK_SIZE = 800;
    private static final int DEFAULT_MAX_CHUNKS = 500;
    private static final int DEFAULT_MAX_CONTENT_LENGTH = 8000;
    private static final int DEFAULT_MAX_METADATA_ENTRIES = 50;
    private static final int DEFAULT_MAX_METADATA_VALUE_LENGTH = 512;

    public SpringAiDocumentIndexingOptions {
        entityType = requiredText(entityType, "entityType");
        sourceId = requiredText(sourceId, "sourceId");
        sourceName = hasText(sourceName) ? sourceName.trim() : sourceId;
        operation = operation != null ? operation : IndexingOperation.CREATE;
        strategy = strategy != null && strategy != IndexingStrategy.AUTO ? strategy : IndexingStrategy.ASYNC;
        actionPlan = actionPlan != null ? actionPlan : new IndexingActionPlan(true, true, false, false, false);
        transformers = transformers == null ? List.of() : List.copyOf(transformers);
        tokenChunkSize = tokenChunkSize > 0 ? tokenChunkSize : DEFAULT_TOKEN_CHUNK_SIZE;
        maxChunks = maxChunks > 0 ? maxChunks : DEFAULT_MAX_CHUNKS;
        maxContentLength = maxContentLength > 0 ? maxContentLength : DEFAULT_MAX_CONTENT_LENGTH;
        maxMetadataEntries = maxMetadataEntries > 0 ? maxMetadataEntries : DEFAULT_MAX_METADATA_ENTRIES;
        maxMetadataValueLength = maxMetadataValueLength > 0
            ? maxMetadataValueLength
            : DEFAULT_MAX_METADATA_VALUE_LENGTH;
        metadata = metadata == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        scheduledFor = scheduledFor != null ? scheduledFor : LocalDateTime.now();
        maxRetries = maxRetries > 0 ? maxRetries : 5;
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String requiredText(String value, String name) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public static final class Builder {
        private String entityType;
        private String sourceId;
        private String sourceName;
        private IndexingOperation operation = IndexingOperation.CREATE;
        private IndexingStrategy strategy = IndexingStrategy.ASYNC;
        private IndexingActionPlan actionPlan = new IndexingActionPlan(true, true, false, false, false);
        private final List<DocumentTransformer> transformers = new ArrayList<>();
        private boolean splitWithTokenTextSplitter = true;
        private int tokenChunkSize = DEFAULT_TOKEN_CHUNK_SIZE;
        private int maxChunks = DEFAULT_MAX_CHUNKS;
        private int maxContentLength = DEFAULT_MAX_CONTENT_LENGTH;
        private int maxMetadataEntries = DEFAULT_MAX_METADATA_ENTRIES;
        private int maxMetadataValueLength = DEFAULT_MAX_METADATA_VALUE_LENGTH;
        private final Map<String, Object> metadata = new LinkedHashMap<>();
        private LocalDateTime scheduledFor;
        private int maxRetries = 5;

        private Builder() {
        }

        public Builder entityType(String entityType) {
            this.entityType = entityType;
            return this;
        }

        public Builder sourceId(String sourceId) {
            this.sourceId = sourceId;
            return this;
        }

        public Builder sourceName(String sourceName) {
            this.sourceName = sourceName;
            return this;
        }

        public Builder operation(IndexingOperation operation) {
            this.operation = operation;
            return this;
        }

        public Builder strategy(IndexingStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        public Builder actionPlan(IndexingActionPlan actionPlan) {
            this.actionPlan = actionPlan;
            return this;
        }

        public Builder addTransformer(DocumentTransformer transformer) {
            if (transformer != null) {
                this.transformers.add(transformer);
            }
            return this;
        }

        public Builder transformers(List<DocumentTransformer> transformers) {
            this.transformers.clear();
            if (transformers != null) {
                transformers.stream()
                    .filter(Objects::nonNull)
                    .forEach(this.transformers::add);
            }
            return this;
        }

        public Builder splitWithTokenTextSplitter(boolean splitWithTokenTextSplitter) {
            this.splitWithTokenTextSplitter = splitWithTokenTextSplitter;
            return this;
        }

        public Builder tokenChunkSize(int tokenChunkSize) {
            this.tokenChunkSize = tokenChunkSize;
            return this;
        }

        public Builder maxChunks(int maxChunks) {
            this.maxChunks = maxChunks;
            return this;
        }

        public Builder maxContentLength(int maxContentLength) {
            this.maxContentLength = maxContentLength;
            return this;
        }

        public Builder maxMetadataEntries(int maxMetadataEntries) {
            this.maxMetadataEntries = maxMetadataEntries;
            return this;
        }

        public Builder maxMetadataValueLength(int maxMetadataValueLength) {
            this.maxMetadataValueLength = maxMetadataValueLength;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata.clear();
            if (metadata != null) {
                this.metadata.putAll(metadata);
            }
            return this;
        }

        public Builder metadata(String key, Object value) {
            if (key != null) {
                this.metadata.put(key, value);
            }
            return this;
        }

        public Builder scheduledFor(LocalDateTime scheduledFor) {
            this.scheduledFor = scheduledFor;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public SpringAiDocumentIndexingOptions build() {
            return new SpringAiDocumentIndexingOptions(
                entityType,
                sourceId,
                sourceName,
                operation,
                strategy,
                actionPlan,
                transformers,
                splitWithTokenTextSplitter,
                tokenChunkSize,
                maxChunks,
                maxContentLength,
                maxMetadataEntries,
                maxMetadataValueLength,
                metadata,
                scheduledFor,
                maxRetries
            );
        }
    }
}
