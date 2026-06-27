package ai.fabric.indexing.document.springai;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.IndexingRequest;
import ai.fabric.indexing.queue.IndexingQueueService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Converts Spring AI ETL documents into AI Fabric indexing queue work.
 */
public class SpringAiDocumentIndexingAdapter {

    private static final String ENTITY_ID_PREFIX = "springai-";
    private static final List<String> SENSITIVE_METADATA_KEY_PARTS = List.of(
        "authorization",
        "credential",
        "password",
        "secret",
        "token",
        "api_key",
        "apikey",
        "url",
        "uri",
        "path",
        "prompt",
        "completion"
    );

    private final ObjectMapper objectMapper;
    private final IndexingQueueService queueService;
    private final AIEntityConfigurationLoader configurationLoader;

    public SpringAiDocumentIndexingAdapter(ObjectMapper objectMapper,
                                           IndexingQueueService queueService,
                                           AIEntityConfigurationLoader configurationLoader) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.queueService = Objects.requireNonNull(queueService, "queueService is required");
        this.configurationLoader = Objects.requireNonNull(configurationLoader, "configurationLoader is required");
    }

    public List<IndexingQueueEntry> enqueue(DocumentReader reader, SpringAiDocumentIndexingOptions options) {
        return enqueue(readDocuments(reader), options);
    }

    public List<IndexingQueueEntry> enqueue(List<Document> documents, SpringAiDocumentIndexingOptions options) {
        List<IndexingRequest> requests = toIndexingRequests(documents, options);
        List<IndexingQueueEntry> entries = new ArrayList<>(requests.size());
        for (IndexingRequest request : requests) {
            entries.add(queueService.enqueue(request));
        }
        return List.copyOf(entries);
    }

    public List<IndexingRequest> toIndexingRequests(DocumentReader reader, SpringAiDocumentIndexingOptions options) {
        return toIndexingRequests(readDocuments(reader), options);
    }

    public List<IndexingRequest> toIndexingRequests(List<Document> documents, SpringAiDocumentIndexingOptions options) {
        SpringAiDocumentIndexingOptions resolved = Objects.requireNonNull(options, "options is required");
        validateEntityType(resolved.entityType());

        List<Document> transformed = transform(documents, resolved);
        List<Document> textDocuments = transformed.stream()
            .filter(Objects::nonNull)
            .filter(Document::isText)
            .filter(document -> StringUtils.hasText(document.getText()))
            .toList();

        if (textDocuments.size() > resolved.maxChunks()) {
            throw new IllegalArgumentException("Spring AI document ingestion produced "
                + textDocuments.size() + " chunks; maxChunks=" + resolved.maxChunks());
        }

        List<IndexingRequest> requests = new ArrayList<>(textDocuments.size());
        int chunkCount = textDocuments.size();
        for (int i = 0; i < chunkCount; i++) {
            requests.add(toIndexingRequest(textDocuments.get(i), resolved, i, chunkCount));
        }
        return List.copyOf(requests);
    }

    private List<Document> readDocuments(DocumentReader reader) {
        Objects.requireNonNull(reader, "reader is required");
        List<Document> documents = reader.read();
        return documents == null ? List.of() : documents;
    }

    private void validateEntityType(String entityType) {
        AIEntityConfig config = configurationLoader.getEntityConfig(entityType);
        if (config == null) {
            throw new IllegalArgumentException("Unknown AI Fabric entityType/vector space: " + entityType);
        }
        if (!config.isIndexable()) {
            throw new IllegalArgumentException("AI Fabric entityType is not indexable: " + entityType);
        }
    }

    private List<Document> transform(List<Document> documents, SpringAiDocumentIndexingOptions options) {
        List<Document> current = documents == null ? List.of() : List.copyOf(documents);
        for (DocumentTransformer transformer : options.transformers()) {
            current = safeTransform(transformer, current);
        }
        if (options.splitWithTokenTextSplitter()) {
            TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(options.tokenChunkSize())
                .withMaxNumChunks(options.maxChunks())
                .build();
            current = splitter.split(current);
        }
        return current;
    }

    private List<Document> safeTransform(DocumentTransformer transformer, List<Document> documents) {
        List<Document> transformed = transformer.transform(documents);
        return transformed == null ? List.of() : transformed;
    }

    private IndexingRequest toIndexingRequest(Document document,
                                              SpringAiDocumentIndexingOptions options,
                                              int chunkIndex,
                                              int chunkCount) {
        String content = document.getText().trim();
        if (content.length() > options.maxContentLength()) {
            throw new IllegalArgumentException("Spring AI document chunk exceeds maxContentLength="
                + options.maxContentLength());
        }

        MetadataSanitization sanitizedMetadata = sanitizeMetadata(document.getMetadata(), options);
        String documentId = StringUtils.hasText(document.getId()) ? document.getId().trim() : "document-" + chunkIndex;
        String entityId = stableEntityId(options.sourceId(), documentId, chunkIndex);
        SpringAiIndexingDocument payload = new SpringAiIndexingDocument(
            entityId,
            content,
            options.sourceId(),
            options.sourceName(),
            documentId,
            chunkIndex,
            chunkCount,
            sha256(content),
            sanitizedMetadata.droppedCount(),
            sanitizedMetadata.metadata()
        );

        return IndexingRequest.builder()
            .entityType(options.entityType())
            .entityId(entityId)
            .entityClassName(SpringAiIndexingDocument.class.getName())
            .operation(options.operation())
            .strategy(options.strategy())
            .actionPlan(options.actionPlan())
            .payload(writePayload(payload))
            .scheduledFor(options.scheduledFor())
            .maxRetries(options.maxRetries())
            .build();
    }

    private String writePayload(SpringAiIndexingDocument payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize Spring AI indexing document", ex);
        }
    }

    private MetadataSanitization sanitizeMetadata(Map<String, Object> documentMetadata,
                                                  SpringAiDocumentIndexingOptions options) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (documentMetadata != null) {
            merged.putAll(documentMetadata);
        }
        merged.putAll(options.metadata());

        Map<String, Object> sanitized = new LinkedHashMap<>();
        int dropped = 0;
        for (Map.Entry<String, Object> entry : merged.entrySet()) {
            if (sanitized.size() >= options.maxMetadataEntries()) {
                dropped++;
                continue;
            }

            String key = safeMetadataKey(entry.getKey());
            if (!StringUtils.hasText(key) || isSensitiveMetadataKey(key)) {
                dropped++;
                continue;
            }

            Object value = safeMetadataValue(entry.getValue(), options.maxMetadataValueLength());
            if (value == null) {
                dropped++;
                continue;
            }
            sanitized.put(key, value);
        }
        return new MetadataSanitization(Map.copyOf(sanitized), dropped);
    }

    private String safeMetadataKey(String key) {
        if (!StringUtils.hasText(key)) {
            return "";
        }
        String trimmed = key.trim();
        if (trimmed.length() > 96) {
            return "";
        }
        return trimmed;
    }

    private boolean isSensitiveMetadataKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return SENSITIVE_METADATA_KEY_PARTS.stream().anyMatch(normalized::contains);
    }

    private Object safeMetadataValue(Object value, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            return value;
        }
        if (value instanceof Float number) {
            return Float.isFinite(number) ? number.doubleValue() : null;
        }
        if (value instanceof Double number) {
            return Double.isFinite(number) ? number : null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Enum<?> enumValue) {
            return boundedString(enumValue.name(), maxLength);
        }
        if (value instanceof Character character) {
            return character.toString();
        }
        if (value instanceof TemporalAccessor) {
            return boundedString(value.toString(), maxLength);
        }
        if (value instanceof CharSequence text) {
            return boundedString(text.toString(), maxLength);
        }
        return null;
    }

    private String boundedString(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private String stableEntityId(String sourceId, String documentId, int chunkIndex) {
        return ENTITY_ID_PREFIX + sha256(sourceId + "|" + documentId + "|" + chunkIndex).substring(0, 40);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private record MetadataSanitization(Map<String, Object> metadata, int droppedCount) {
    }
}
