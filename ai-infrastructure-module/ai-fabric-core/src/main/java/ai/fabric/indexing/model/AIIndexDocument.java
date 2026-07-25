package ai.fabric.indexing.model;

import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.api.AIProcessOperation;

import java.time.Instant;
import java.util.Collections;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Versioned, class-free queue payload containing only approved projected data.
 */
public record AIIndexDocument(
    int schemaVersion,
    String descriptorHash,
    String entityType,
    String entityId,
    AIIndexWorkType workType,
    AIProcessOperation sourceOperation,
    String semanticSearchText,
    String ragContextText,
    Map<String, Object> vectorMetadata,
    Map<String, AIContextValue> llmContext,
    Map<String, Object> responseMetadata,
    Long sourceVersion,
    String correlationId,
    Instant occurredAt
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MAX_ENTITY_TYPE_LENGTH = 128;
    public static final int MAX_ENTITY_ID_LENGTH = 512;
    public static final int MAX_CORRELATION_ID_LENGTH = 128;
    public static final int MAX_METADATA_KEY_LENGTH = 128;

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public AIIndexDocument {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported AI index document schema " + schemaVersion);
        }
        descriptorHash = requireBoundedText(
            descriptorHash,
            "descriptorHash",
            64
        );
        if (!SHA_256.matcher(descriptorHash).matches()) {
            throw new IllegalArgumentException(
                "descriptorHash must be a lowercase SHA-256 value"
            );
        }
        entityType = requireBoundedText(
            entityType,
            "entityType",
            MAX_ENTITY_TYPE_LENGTH
        );
        entityId = requireBoundedText(
            entityId,
            "entityId",
            MAX_ENTITY_ID_LENGTH
        );
        Objects.requireNonNull(workType, "workType is required");
        Objects.requireNonNull(sourceOperation, "sourceOperation is required");
        vectorMetadata = immutableOrderedMap(vectorMetadata, "vectorMetadata");
        llmContext = immutableOrderedMap(llmContext, "llmContext");
        responseMetadata = immutableOrderedMap(responseMetadata, "responseMetadata");
        correlationId = correlationId == null ? "" : correlationId.trim();
        if (correlationId.length() > MAX_CORRELATION_ID_LENGTH) {
            throw new IllegalArgumentException(
                "correlationId must not exceed " + MAX_CORRELATION_ID_LENGTH
            );
        }
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        if (sourceVersion != null && sourceVersion < 0) {
            throw new IllegalArgumentException("sourceVersion must not be negative");
        }

        switch (workType) {
            case UPSERT -> {
                if (sourceOperation != AIProcessOperation.CREATE
                    && sourceOperation != AIProcessOperation.UPDATE) {
                    throw new IllegalArgumentException(
                        "UPSERT sourceOperation must be CREATE or UPDATE"
                    );
                }
                semanticSearchText = requireText(
                    semanticSearchText,
                    "semanticSearchText is required for UPSERT"
                );
                ragContextText = normalizeOptionalText(ragContextText);
            }
            case DELETE -> {
                if (sourceOperation != AIProcessOperation.DELETE) {
                    throw new IllegalArgumentException(
                        "DELETE work requires DELETE sourceOperation"
                    );
                }
                if (hasText(semanticSearchText)
                    || hasText(ragContextText)
                    || !vectorMetadata.isEmpty()
                    || !llmContext.isEmpty()
                    || !responseMetadata.isEmpty()
                    || sourceVersion != null) {
                    throw new IllegalArgumentException(
                        "DELETE work must not contain projected entity data"
                    );
                }
                semanticSearchText = null;
                ragContextText = null;
            }
            case ANALYZE -> {
                if (sourceOperation == AIProcessOperation.DELETE) {
                    throw new IllegalArgumentException(
                        "ANALYZE work cannot originate from DELETE"
                    );
                }
                semanticSearchText = requireText(
                    semanticSearchText,
                    "semanticSearchText is required for ANALYZE"
                );
                ragContextText = normalizeOptionalText(ragContextText);
            }
        }
    }

    private static <V> Map<String, V> immutableOrderedMap(
        Map<String, V> source,
        String field
    ) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, V> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String safeKey = requireBoundedText(
                key,
                field + " key",
                MAX_METADATA_KEY_LENGTH
            );
            Objects.requireNonNull(value, field + " value is required");
            if (value instanceof AIContextValue contextValue) {
                validateJsonValue(contextValue.value(), field + "." + safeKey);
            } else {
                validateJsonValue(value, field + "." + safeKey);
            }
            if (result.putIfAbsent(safeKey, value) != null) {
                throw new IllegalArgumentException(
                    field + " contains duplicate normalized key " + safeKey
                );
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static void validateJsonValue(Object value, String path) {
        Objects.requireNonNull(value, path + " is required");
        if (value instanceof String
            || value instanceof Boolean
            || value instanceof Number) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, nested) -> {
                if (!(key instanceof String textKey)) {
                    throw new IllegalArgumentException(
                        path + " contains a non-string map key"
                    );
                }
                requireBoundedText(
                    textKey,
                    path + " key",
                    MAX_METADATA_KEY_LENGTH
                );
                validateJsonValue(nested, path + "." + textKey);
            });
            return;
        }
        if (value instanceof Collection<?> collection) {
            int index = 0;
            for (Object nested : collection) {
                validateJsonValue(nested, path + "[" + index++ + "]");
            }
            return;
        }
        throw new IllegalArgumentException(
            path + " contains unsupported payload type " + value.getClass().getName()
        );
    }

    private static String requireBoundedText(
        String value,
        String field,
        int maxLength
    ) {
        String candidate = Objects.requireNonNull(value, field + " is required").trim();
        if (candidate.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (candidate.length() > maxLength) {
            throw new IllegalArgumentException(
                field + " must not exceed " + maxLength + " characters"
            );
        }
        return candidate;
    }

    private static String requireText(String value, String message) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String normalizeOptionalText(String value) {
        return hasText(value) ? value.trim() : "";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
