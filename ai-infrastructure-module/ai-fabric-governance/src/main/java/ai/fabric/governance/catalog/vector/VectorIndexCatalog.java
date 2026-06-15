package ai.fabric.governance.catalog.vector;

import ai.fabric.dto.VectorRecord;
import ai.fabric.dto.VectorScanPage;
import ai.fabric.dto.VectorScanRequest;
import ai.fabric.governance.catalog.IndexCatalog;
import ai.fabric.governance.catalog.IndexCatalogEntry;
import ai.fabric.governance.catalog.IndexCatalogScanPage;
import ai.fabric.governance.catalog.IndexCatalogScanRequest;
import ai.fabric.rag.VectorDatabaseService;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Vector-backed catalog that derives inventory directly from the vector database (scan/exist/delete).
 *
 * <p>Note: upsert is not required because the catalog is derived from the vector store.</p>
 */
@RequiredArgsConstructor
public class VectorIndexCatalog implements IndexCatalog {

    private final VectorDatabaseService vectorDatabaseService;

    @Override
    public void upsert(IndexCatalogEntry entry) {
        // No-op: catalog is derived from vector store.
    }

    @Override
    public void delete(String entityType, String entityId) {
        vectorDatabaseService.removeVector(entityType, entityId);
    }

    @Override
    public boolean exists(String entityType, String entityId) {
        return vectorDatabaseService.vectorExists(entityType, entityId);
    }

    @Override
    public IndexCatalogScanPage scan(IndexCatalogScanRequest request) {
        VectorScanPage page = vectorDatabaseService.scan(VectorScanRequest.builder()
            .entityType(request.getEntityType())
            .metadataEquals(request.getMetadataEquals())
            .limit(request.getLimit())
            .cursor(request.getCursor())
            .includeContent(false)
            .includeEmbedding(false)
            .includeMetadata(true)
            .build());

        List<IndexCatalogEntry> entries = page.getVectors().stream()
            .map(this::toEntry)
            .toList();

        return IndexCatalogScanPage.builder()
            .entries(entries)
            .nextCursor(page.getNextCursor())
            .hasMore(page.isHasMore())
            .build();
    }

    private IndexCatalogEntry toEntry(VectorRecord record) {
        Map<String, Object> metadata = record.getMetadata();
        LocalDateTime createdAt = record.getCreatedAt();
        LocalDateTime updatedAt = record.getUpdatedAt();
        return IndexCatalogEntry.builder()
            .entityType(record.getEntityType())
            .entityId(record.getEntityId())
            .vectorId(record.getVectorId())
            .indexedCreatedAt(createdAt)
            .indexedUpdatedAt(updatedAt)
            .metadata(metadata)
            .build();
    }
}

