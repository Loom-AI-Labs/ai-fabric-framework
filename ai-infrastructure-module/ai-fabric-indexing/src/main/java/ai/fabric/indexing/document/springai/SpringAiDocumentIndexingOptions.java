package ai.fabric.indexing.document.springai;

import ai.fabric.indexing.api.AIProcessOperation;
import org.springframework.ai.document.DocumentTransformer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Trusted server-side options for preparing Spring AI documents. */
public record SpringAiDocumentIndexingOptions(
    String entityType,
    String sourceId,
    long sourceVersion,
    String sourceName,
    String tenantId,
    String visibility,
    AIProcessOperation operation,
    List<DocumentTransformer> transformers,
    boolean splitWithTokenTextSplitter,
    int tokenChunkSize,
    int maxDocuments,
    int maxChunks,
    int maxContentLength,
    int maxTotalContentLength,
    int maxMetadataEntries,
    int maxMetadataValueLength,
    Set<String> allowedMetadataKeys,
    Map<String, Object> metadata,
    String correlationId,
    Instant occurredAt
) {

    public static final int DEFAULT_TOKEN_CHUNK_SIZE = 800;
    public static final int DEFAULT_MAX_DOCUMENTS = 100;
    public static final int DEFAULT_MAX_CHUNKS = 500;
    public static final int DEFAULT_MAX_CONTENT_LENGTH = 10_000;
    public static final int DEFAULT_MAX_TOTAL_CONTENT_LENGTH = 1_000_000;
    public static final int DEFAULT_MAX_METADATA_ENTRIES = 32;
    public static final int DEFAULT_MAX_METADATA_VALUE_LENGTH = 512;

    public SpringAiDocumentIndexingOptions {
        entityType = boundedRequired(entityType, "entityType", 128);
        sourceId = boundedRequired(sourceId, "sourceId", 256);
        if (sourceVersion < 0) {
            throw new IllegalArgumentException("sourceVersion must not be negative");
        }
        sourceName = hasText(sourceName)
            ? boundedRequired(sourceName, "sourceName", 512)
            : sourceId;
        tenantId = normalizeOptional(tenantId, "tenantId", 256);
        visibility = normalizeOptional(visibility, "visibility", 128);
        operation = operation == null
            ? AIProcessOperation.CREATE
            : operation;
        if (operation == AIProcessOperation.DELETE) {
            throw new IllegalArgumentException(
                "Document preparation cannot use DELETE"
            );
        }
        transformers = transformers == null
            ? List.of()
            : List.copyOf(transformers);
        tokenChunkSize = positiveOrDefault(
            tokenChunkSize,
            DEFAULT_TOKEN_CHUNK_SIZE
        );
        maxDocuments = positiveOrDefault(
            maxDocuments,
            DEFAULT_MAX_DOCUMENTS
        );
        maxChunks = positiveOrDefault(maxChunks, DEFAULT_MAX_CHUNKS);
        maxContentLength = positiveOrDefault(
            maxContentLength,
            DEFAULT_MAX_CONTENT_LENGTH
        );
        maxTotalContentLength = positiveOrDefault(
            maxTotalContentLength,
            DEFAULT_MAX_TOTAL_CONTENT_LENGTH
        );
        if (maxTotalContentLength < maxContentLength) {
            throw new IllegalArgumentException(
                "maxTotalContentLength must be greater than or equal to maxContentLength"
            );
        }
        maxMetadataEntries = positiveOrDefault(
            maxMetadataEntries,
            DEFAULT_MAX_METADATA_ENTRIES
        );
        maxMetadataValueLength = positiveOrDefault(
            maxMetadataValueLength,
            DEFAULT_MAX_METADATA_VALUE_LENGTH
        );
        allowedMetadataKeys = immutableKeys(allowedMetadataKeys);
        metadata = metadata == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        correlationId = normalizeOptional(
            correlationId,
            "correlationId",
            128
        );
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    private static Set<String> immutableKeys(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (hasText(value)) {
                normalized.add(boundedRequired(
                    value,
                    "allowedMetadataKey",
                    128
                ));
            }
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static int positiveOrDefault(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    private static String boundedRequired(
        String value,
        String name,
        int maxLength
    ) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                name + " must not exceed " + maxLength + " characters"
            );
        }
        return normalized;
    }

    private static String normalizeOptional(
        String value,
        String name,
        int maxLength
    ) {
        return hasText(value)
            ? boundedRequired(value, name, maxLength)
            : "";
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public static final class Builder {
        private String entityType;
        private String sourceId;
        private long sourceVersion;
        private String sourceName;
        private String tenantId;
        private String visibility;
        private AIProcessOperation operation = AIProcessOperation.CREATE;
        private final List<DocumentTransformer> transformers = new ArrayList<>();
        private boolean splitWithTokenTextSplitter = true;
        private int tokenChunkSize = DEFAULT_TOKEN_CHUNK_SIZE;
        private int maxDocuments = DEFAULT_MAX_DOCUMENTS;
        private int maxChunks = DEFAULT_MAX_CHUNKS;
        private int maxContentLength = DEFAULT_MAX_CONTENT_LENGTH;
        private int maxTotalContentLength = DEFAULT_MAX_TOTAL_CONTENT_LENGTH;
        private int maxMetadataEntries = DEFAULT_MAX_METADATA_ENTRIES;
        private int maxMetadataValueLength = DEFAULT_MAX_METADATA_VALUE_LENGTH;
        private final Set<String> allowedMetadataKeys = new LinkedHashSet<>();
        private final Map<String, Object> metadata = new LinkedHashMap<>();
        private String correlationId;
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

        public Builder sourceVersion(long value) {
            sourceVersion = value;
            return this;
        }

        public Builder sourceName(String value) {
            sourceName = value;
            return this;
        }

        public Builder tenantId(String value) {
            tenantId = value;
            return this;
        }

        public Builder visibility(String value) {
            visibility = value;
            return this;
        }

        public Builder operation(AIProcessOperation value) {
            operation = value;
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
                values.stream()
                    .filter(Objects::nonNull)
                    .forEach(transformers::add);
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

        public Builder maxDocuments(int value) {
            maxDocuments = value;
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

        public Builder maxTotalContentLength(int value) {
            maxTotalContentLength = value;
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

        public Builder allowedMetadataKey(String value) {
            if (value != null) {
                allowedMetadataKeys.add(value);
            }
            return this;
        }

        public Builder allowedMetadataKeys(Set<String> values) {
            allowedMetadataKeys.clear();
            if (values != null) {
                allowedMetadataKeys.addAll(values);
            }
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

        public Builder correlationId(String value) {
            correlationId = value;
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
                sourceVersion,
                sourceName,
                tenantId,
                visibility,
                operation,
                transformers,
                splitWithTokenTextSplitter,
                tokenChunkSize,
                maxDocuments,
                maxChunks,
                maxContentLength,
                maxTotalContentLength,
                maxMetadataEntries,
                maxMetadataValueLength,
                allowedMetadataKeys,
                metadata,
                correlationId,
                occurredAt
            );
        }
    }
}
