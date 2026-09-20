package ai.fabric.indexing.document.model;

import java.time.Instant;
import java.util.List;

/** Immutable server-side preparation result. This type contains full chunk content. */
public record DocumentIngestionPlan(
    String planId,
    String sourceId,
    long sourceVersion,
    String sourceName,
    String entityType,
    String tenantId,
    String visibility,
    Instant createdAt,
    int documentCount,
    int totalContentLength,
    List<DocumentIngestionChunk> chunks,
    List<DocumentIngestionWarning> warnings
) {
    public DocumentIngestionPlan {
        planId = requireText(planId, "planId");
        sourceId = requireText(sourceId, "sourceId");
        if (sourceVersion < 0) {
            throw new IllegalArgumentException("sourceVersion must not be negative");
        }
        sourceName = normalizeOptional(sourceName, sourceId);
        entityType = requireText(entityType, "entityType");
        tenantId = normalizeOptional(tenantId, "");
        visibility = normalizeOptional(visibility, "");
        createdAt = createdAt == null ? Instant.now() : createdAt;
        if (documentCount <= 0) {
            throw new IllegalArgumentException("documentCount must be positive");
        }
        if (totalContentLength <= 0) {
            throw new IllegalArgumentException("totalContentLength must be positive");
        }
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks must not be empty");
        }
        int actualContentLength = chunks.stream()
            .mapToInt(DocumentIngestionChunk::contentLength)
            .sum();
        if (actualContentLength != totalContentLength) {
            throw new IllegalArgumentException(
                "totalContentLength must match the prepared chunks"
            );
        }
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
