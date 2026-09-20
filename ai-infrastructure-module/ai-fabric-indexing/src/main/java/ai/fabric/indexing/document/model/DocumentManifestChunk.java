package ai.fabric.indexing.document.model;

/** Exact chunk identity retained for attribution and deletion. */
public record DocumentManifestChunk(
    String sourceDocumentId,
    String chunkId,
    int chunkIndex,
    String entityId,
    String contentFingerprint
) {
    public DocumentManifestChunk {
        sourceDocumentId = requireText(sourceDocumentId, "sourceDocumentId");
        chunkId = requireText(chunkId, "chunkId");
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must not be negative");
        }
        entityId = requireText(entityId, "entityId");
        contentFingerprint = requireText(contentFingerprint, "contentFingerprint");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
