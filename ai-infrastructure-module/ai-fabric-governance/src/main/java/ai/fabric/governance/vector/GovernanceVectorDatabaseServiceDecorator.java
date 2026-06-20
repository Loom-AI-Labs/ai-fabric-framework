package ai.fabric.governance.vector;

import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.VectorRecord;
import ai.fabric.dto.VectorScanPage;
import ai.fabric.dto.VectorScanRequest;
import ai.fabric.governance.catalog.IndexCatalog;
import ai.fabric.governance.catalog.IndexCatalogEntry;
import ai.fabric.governance.catalog.disabled.DisabledIndexCatalog;
import ai.fabric.governance.catalog.vector.VectorIndexCatalog;
import ai.fabric.governance.metadata.GovernanceVectorMetadata;
import ai.fabric.rag.VectorDatabaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class GovernanceVectorDatabaseServiceDecorator implements VectorDatabaseService {

    private static final int CATALOG_WRITE_MAX_ATTEMPTS = 3;
    private static final long CATALOG_WRITE_BACKOFF_MS = 50L;

    private final VectorDatabaseService delegate;
    private final ObjectProvider<IndexCatalog> indexCatalogProvider;

    @Override
    public boolean supportsVectorScan() {
        return delegate.supportsVectorScan();
    }

    @Override
    public boolean supportsMetadataFiltering() {
        return delegate.supportsMetadataFiltering();
    }

    @Override
    public boolean supportsSearchMetadataFiltering() {
        return delegate.supportsSearchMetadataFiltering();
    }

    @Override
    public boolean supportsScanMetadataFiltering() {
        return delegate.supportsScanMetadataFiltering();
    }

    @Override
    public boolean supportsExactFetchById() {
        return delegate.supportsExactFetchById();
    }

    @Override
    public boolean supportsClearByEntityType() {
        return delegate.supportsClearByEntityType();
    }

    @Override
    public boolean supportsEfficientEntityTypeCount() {
        return delegate.supportsEfficientEntityTypeCount();
    }

    @Override
    public Map<String, Object> adminDiagnostics() {
        Map<String, Object> delegateDiagnostics = delegate.adminDiagnostics();
        Map<String, Object> diagnostics = delegateDiagnostics == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(delegateDiagnostics);
        diagnostics.put("governanceDecorated", true);
        diagnostics.put("governanceDecoratorClass", getClass().getName());
        diagnostics.put("governanceCatalogWriteThrough", sqlCatalogOrNull() != null);
        return diagnostics;
    }

    @Override
    public String storeVector(String entityType, String entityId, String content, List<Double> embedding, Map<String, Object> metadata) {
        Map<String, Object> enrichedMetadata = withStableIndexTimestamps(entityType, entityId, metadata);
        String vectorId = delegate.storeVector(entityType, entityId, content, embedding, enrichedMetadata);
        upsertCatalog(entityType, entityId, vectorId, enrichedMetadata);
        return vectorId;
    }

    @Override
    public boolean updateVector(String vectorId, String entityType, String entityId, String content, List<Double> embedding, Map<String, Object> metadata) {
        Map<String, Object> enrichedMetadata = withStableIndexTimestamps(entityType, entityId, metadata);
        boolean updated = delegate.updateVector(vectorId, entityType, entityId, content, embedding, enrichedMetadata);
        if (updated) {
            upsertCatalog(entityType, entityId, vectorId, enrichedMetadata);
        }
        return updated;
    }

    @Override
    public Optional<VectorRecord> getVector(String vectorId) {
        return delegate.getVector(vectorId);
    }

    @Override
    public Optional<VectorRecord> getVectorByEntity(String entityType, String entityId) {
        return delegate.getVectorByEntity(entityType, entityId);
    }

    @Override
    public AISearchResponse search(List<Double> queryVector, AISearchRequest request) {
        return delegate.search(queryVector, request);
    }

    @Override
    public AISearchResponse searchByEntityType(List<Double> queryVector, String entityType, int limit, double threshold) {
        return delegate.searchByEntityType(queryVector, entityType, limit, threshold);
    }

    @Override
    public boolean removeVector(String entityType, String entityId) {
        boolean removed = delegate.removeVector(entityType, entityId);
        if (removed) {
            IndexCatalog catalog = sqlCatalogOrNull();
            if (catalog != null) {
                runCatalogWrite("catalog.delete(" + entityType + "," + entityId + ")",
                    () -> catalog.delete(entityType, entityId));
            }
        }
        return removed;
    }

    @Override
    public boolean removeVectorById(String vectorId) {
        Optional<VectorRecord> existing = delegate.getVector(vectorId);
        boolean removed = delegate.removeVectorById(vectorId);
        if (removed && existing.isPresent()) {
            IndexCatalog catalog = sqlCatalogOrNull();
            if (catalog != null) {
                VectorRecord record = existing.get();
                runCatalogWrite("catalog.delete(" + record.getEntityType() + "," + record.getEntityId() + ")",
                    () -> catalog.delete(record.getEntityType(), record.getEntityId()));
            }
        }
        return removed;
    }

    @Override
    public List<String> batchStoreVectors(List<VectorRecord> vectors) {
        if (vectors != null) {
            vectors.forEach(this::enrichRecordMetadataInPlace);
        }
        List<String> ids = delegate.batchStoreVectors(vectors);
        if (vectors != null) {
            for (int i = 0; i < vectors.size() && i < ids.size(); i++) {
                VectorRecord record = vectors.get(i);
                upsertCatalog(record.getEntityType(), record.getEntityId(), ids.get(i), record.getMetadata());
            }
        }
        return ids;
    }

    @Override
    public int batchUpdateVectors(List<VectorRecord> vectors) {
        if (vectors != null) {
            vectors.forEach(this::enrichRecordMetadataInPlace);
        }
        int updated = delegate.batchUpdateVectors(vectors);
        if (vectors != null) {
            for (VectorRecord record : vectors) {
                if (record.getVectorId() != null) {
                    upsertCatalog(record.getEntityType(), record.getEntityId(), record.getVectorId(), record.getMetadata());
                }
            }
        }
        return updated;
    }

    @Override
    public int batchRemoveVectors(List<String> vectorIds) {
        List<VectorRecord> existing = vectorIds == null
            ? List.of()
            : vectorIds.stream()
            .map(delegate::getVector)
            .flatMap(Optional::stream)
            .toList();
        int removed = delegate.batchRemoveVectors(vectorIds);

        IndexCatalog catalog = sqlCatalogOrNull();
        if (catalog != null) {
            existing.forEach(record ->
                runCatalogWrite("catalog.delete(" + record.getEntityType() + "," + record.getEntityId() + ")",
                    () -> catalog.delete(record.getEntityType(), record.getEntityId())));
        }
        return removed;
    }

    @Override
    public List<VectorRecord> getVectorsByEntityType(String entityType) {
        return delegate.getVectorsByEntityType(entityType);
    }

    @Override
    public long getVectorCountByEntityType(String entityType) {
        return delegate.getVectorCountByEntityType(entityType);
    }

    @Override
    public boolean vectorExists(String entityType, String entityId) {
        return delegate.vectorExists(entityType, entityId);
    }

    @Override
    public VectorScanPage scan(VectorScanRequest request) {
        return delegate.scan(request);
    }

    @Override
    public Map<String, Object> getStatistics() {
        return delegate.getStatistics();
    }

    @Override
    public long clearVectors() {
        long cleared = delegate.clearVectors();
        IndexCatalog catalog = sqlCatalogOrNull();
        if (catalog != null) {
            try {
                catalog.deleteAll();
            } catch (Exception ex) {
                log.debug("Catalog deleteAll failed during clearVectors", ex);
            }
        }
        return cleared;
    }

    @Override
    public long clearVectorsByEntityType(String entityType) {
        long cleared = delegate.clearVectorsByEntityType(entityType);
        IndexCatalog catalog = sqlCatalogOrNull();
        if (catalog != null) {
            try {
                catalog.deleteByEntityType(entityType);
            } catch (Exception ex) {
                log.debug("Catalog deleteByEntityType failed during clearVectorsByEntityType({})", entityType, ex);
            }
        }
        return cleared;
    }

    private void enrichRecordMetadataInPlace(VectorRecord record) {
        if (record == null) {
            return;
        }
        String preservedCreatedAt = null;
        Map<String, Object> metadata = record.getMetadata();
        if (metadata != null) {
            Object existing = metadata.get(GovernanceVectorMetadata.INDEXED_CREATED_AT);
            preservedCreatedAt = existing != null ? existing.toString() : null;
        }
        record.setMetadata(GovernanceVectorMetadata.enrichIndexTimestamps(metadata, preservedCreatedAt));
    }

    private Map<String, Object> withStableIndexTimestamps(String entityType, String entityId, Map<String, Object> metadata) {
        String preservedCreatedAt = null;
        if (metadata != null) {
            Object existing = metadata.get(GovernanceVectorMetadata.INDEXED_CREATED_AT);
            preservedCreatedAt = existing != null ? existing.toString() : null;
        }
        if (preservedCreatedAt == null && entityType != null && entityId != null) {
            preservedCreatedAt = delegate.getVectorByEntity(entityType, entityId)
                .map(VectorRecord::getMetadata)
                .map(existingMeta -> existingMeta != null ? existingMeta.get(GovernanceVectorMetadata.INDEXED_CREATED_AT) : null)
                .map(val -> val != null ? val.toString() : null)
                .orElse(null);
        }
        return GovernanceVectorMetadata.enrichIndexTimestamps(metadata, preservedCreatedAt);
    }

    private void upsertCatalog(String entityType, String entityId, String vectorId, Map<String, Object> metadata) {
        IndexCatalog catalog = sqlCatalogOrNull();
        if (catalog == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime createdAt = GovernanceVectorMetadata.readIndexedCreatedAt(metadata);
        LocalDateTime updatedAt = GovernanceVectorMetadata.readIndexedUpdatedAt(metadata);
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }

        IndexCatalogEntry entry = IndexCatalogEntry.builder()
            .entityType(entityType)
            .entityId(entityId)
            .vectorId(vectorId)
            .indexedCreatedAt(createdAt)
            .indexedUpdatedAt(updatedAt)
            .metadata(metadata)
            .build();
        runCatalogWrite("catalog.upsert(" + entityType + "," + entityId + ")",
            () -> catalog.upsert(entry));
    }

    private IndexCatalog sqlCatalogOrNull() {
        IndexCatalog catalog = indexCatalogProvider.getIfAvailable();
        if (catalog == null || catalog instanceof DisabledIndexCatalog || catalog instanceof VectorIndexCatalog) {
            return null;
        }
        return catalog;
    }

    private void runCatalogWrite(String operation, Runnable runnable) {
        if (runnable == null) {
            return;
        }

        RuntimeException last = null;
        for (int attempt = 1; attempt <= CATALOG_WRITE_MAX_ATTEMPTS; attempt++) {
            try {
                runnable.run();
                return;
            } catch (RuntimeException ex) {
                last = ex;
                if (attempt >= CATALOG_WRITE_MAX_ATTEMPTS) {
                    break;
                }
                log.warn("Governance catalog write failed (attempt {}/{}): {} ({})",
                    attempt, CATALOG_WRITE_MAX_ATTEMPTS, operation, ex.getMessage());
                log.debug("Governance catalog write failure details", ex);
                try {
                    Thread.sleep(CATALOG_WRITE_BACKOFF_MS * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }

        if (last != null) {
            log.error("Governance catalog write failed after {} attempts: {} ({})",
                CATALOG_WRITE_MAX_ATTEMPTS, operation, last.getMessage());
            log.debug("Governance catalog write failure details", last);
            throw last;
        }
    }
}
