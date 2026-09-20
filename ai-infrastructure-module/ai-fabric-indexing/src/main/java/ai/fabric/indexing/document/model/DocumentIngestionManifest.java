package ai.fabric.indexing.document.model;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Persistable content-free manifest for exact document chunk deletion. */
public record DocumentIngestionManifest(
    int schemaVersion,
    String manifestId,
    String planId,
    String sourceId,
    long sourceVersion,
    String sourceName,
    String entityType,
    String tenantId,
    String visibility,
    Instant createdAt,
    List<DocumentManifestChunk> chunks
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public DocumentIngestionManifest {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                "Unsupported document manifest schema " + schemaVersion
            );
        }
        manifestId = requireText(manifestId, "manifestId");
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
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks must not be empty");
        }
        Set<String> entityIds = new HashSet<>();
        for (DocumentManifestChunk chunk : chunks) {
            if (!entityIds.add(chunk.entityId())) {
                throw new IllegalArgumentException(
                    "manifest contains duplicate entityId " + chunk.entityId()
                );
            }
        }
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
