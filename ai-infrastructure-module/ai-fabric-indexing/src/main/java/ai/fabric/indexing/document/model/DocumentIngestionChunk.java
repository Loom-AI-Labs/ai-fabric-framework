package ai.fabric.indexing.document.model;

import ai.fabric.indexing.model.AIIndexDocument;

import java.util.List;
import java.util.Objects;

/** One deterministic chunk in a server-side document-ingestion plan. */
public record DocumentIngestionChunk(
    int documentOrdinal,
    String sourceDocumentId,
    String chunkId,
    int chunkIndex,
    int chunkCount,
    String entityId,
    int contentLength,
    String contentFingerprint,
    AIIndexDocument indexDocument,
    List<DocumentIngestionWarning> warnings
) {
    public DocumentIngestionChunk {
        if (documentOrdinal < 0) {
            throw new IllegalArgumentException("documentOrdinal must not be negative");
        }
        sourceDocumentId = requireText(sourceDocumentId, "sourceDocumentId");
        chunkId = requireText(chunkId, "chunkId");
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must not be negative");
        }
        if (chunkCount <= 0 || chunkIndex >= chunkCount) {
            throw new IllegalArgumentException("chunkCount must contain chunkIndex");
        }
        entityId = requireText(entityId, "entityId");
        if (contentLength <= 0) {
            throw new IllegalArgumentException("contentLength must be positive");
        }
        contentFingerprint = requireText(contentFingerprint, "contentFingerprint");
        indexDocument = Objects.requireNonNull(indexDocument, "indexDocument is required");
        if (!entityId.equals(indexDocument.entityId())) {
            throw new IllegalArgumentException("entityId must match indexDocument.entityId");
        }
        if (indexDocument.semanticSearchText() == null
            || contentLength != indexDocument.semanticSearchText().length()) {
            throw new IllegalArgumentException(
                "contentLength must match indexDocument semantic content"
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
}
