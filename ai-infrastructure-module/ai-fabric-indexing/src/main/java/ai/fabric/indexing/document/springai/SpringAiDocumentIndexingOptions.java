package ai.fabric.indexing.document.springai;

import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingStrategy;
import org.springframework.ai.document.DocumentTransformer;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Options for converting trusted Spring AI documents into approved index documents.
 */
public record SpringAiDocumentIndexingOptions(
    String entityType,
    String sourceId,
    String sourceName,
    AIProcessOperation operation,
    IndexingStrategy strategy,
    List<DocumentTransformer> transformers,
    boolean splitWithTokenTextSplitter,
    int tokenChunkSize,
    int maxChunks,
    int maxContentLength,
    int maxMetadataEntries,
    int maxMetadataValueLength,
    Map<String, Object> metadata,
    LocalDateTime scheduledFor,
    Instant occurredAt
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
        operation = operation == null ? AIProcessOperation.CREATE : operation;
        if (operation == AIProcessOperation.DELETE) {
            throw new IllegalArgumentException("Document ingestion cannot use DELETE");
        }
        strategy = strategy != null && strategy != IndexingStrategy.AUTO
            ? strategy
            : IndexingStrategy.ASYNC;
        transformers = transformers == null ? List.of() : List.copyOf(transformers);
        tokenChunkSize = tokenChunkSize > 0 ? tokenChunkSize : DEFAULT_TOKEN_CHUNK_SIZE;
        maxChunks = maxChunks > 0 ? maxChunks : DEFAULT_MAX_CHUNKS;
        maxContentLength = maxContentLength > 0
            ? maxContentLength
            : DEFAULT_MAX_CONTENT_LENGTH;
        maxMetadataEntries = maxMetadataEntries > 0
            ? maxMetadataEntries
            : DEFAULT_MAX_METADATA_ENTRIES;
        maxMetadataValueLength = maxMetadataValueLength > 0
            ? maxMetadataValueLength
            : DEFAULT_MAX_METADATA_VALUE_LENGTH;
        metadata = metadata == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        scheduledFor = scheduledFor == null
            ? LocalDateTime.now(Clock.systemUTC())
            : scheduledFor;
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
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
        private AIProcessOperation operation = AIProcessOperation.CREATE;
        private IndexingStrategy strategy = IndexingStrategy.ASYNC;
        private final List<DocumentTransformer> transformers = new ArrayList<>();
        private boolean splitWithTokenTextSplitter = true;
        private int tokenChunkSize = DEFAULT_TOKEN_CHUNK_SIZE;
        private int maxChunks = DEFAULT_MAX_CHUNKS;
        private int maxContentLength = DEFAULT_MAX_CONTENT_LENGTH;
        private int maxMetadataEntries = DEFAULT_MAX_METADATA_ENTRIES;
        private int maxMetadataValueLength = DEFAULT_MAX_METADATA_VALUE_LENGTH;
        private final Map<String, Object> metadata = new LinkedHashMap<>();
        private LocalDateTime scheduledFor;
        private Instant occurredAt;

        private Builder() {
        }

        public Builder entityType(String value) {
            entityType = value;
            return this;
        }

        public Builder sourceId(String value) {
            sourceId = value;
            return this;
        }

        public Builder sourceName(String value) {
            sourceName = value;
            return this;
        }

        public Builder operation(AIProcessOperation value) {
            operation = value;
            return this;
        }

        public Builder strategy(IndexingStrategy value) {
            strategy = value;
            return this;
        }

        public Builder addTransformer(DocumentTransformer value) {
            if (value != null) {
                transformers.add(value);
            }
            return this;
        }

        public Builder transformers(List<DocumentTransformer> values) {
            transformers.clear();
            if (values != null) {
                values.stream().filter(Objects::nonNull).forEach(transformers::add);
            }
            return this;
        }

        public Builder splitWithTokenTextSplitter(boolean value) {
            splitWithTokenTextSplitter = value;
            return this;
        }

        public Builder tokenChunkSize(int value) {
            tokenChunkSize = value;
            return this;
        }

        public Builder maxChunks(int value) {
            maxChunks = value;
            return this;
        }

        public Builder maxContentLength(int value) {
            maxContentLength = value;
            return this;
        }

        public Builder maxMetadataEntries(int value) {
            maxMetadataEntries = value;
            return this;
        }

        public Builder maxMetadataValueLength(int value) {
            maxMetadataValueLength = value;
            return this;
        }

        public Builder metadata(Map<String, Object> values) {
            metadata.clear();
            if (values != null) {
                metadata.putAll(values);
            }
            return this;
        }

        public Builder metadata(String key, Object value) {
            if (key != null) {
                metadata.put(key, value);
            }
            return this;
        }

        public Builder scheduledFor(LocalDateTime value) {
            scheduledFor = value;
            return this;
        }

        public Builder occurredAt(Instant value) {
            occurredAt = value;
            return this;
        }

        public SpringAiDocumentIndexingOptions build() {
            return new SpringAiDocumentIndexingOptions(
                entityType,
                sourceId,
                sourceName,
                operation,
                strategy,
                transformers,
                splitWithTokenTextSplitter,
                tokenChunkSize,
                maxChunks,
                maxContentLength,
                maxMetadataEntries,
                maxMetadataValueLength,
                metadata,
                scheduledFor,
                occurredAt
            );
        }
    }
}
