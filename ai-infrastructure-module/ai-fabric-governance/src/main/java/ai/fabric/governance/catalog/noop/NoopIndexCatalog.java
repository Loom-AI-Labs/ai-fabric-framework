package ai.fabric.governance.catalog.noop;

import ai.fabric.governance.catalog.IndexCatalog;
import ai.fabric.governance.catalog.IndexCatalogEntry;
import ai.fabric.governance.catalog.IndexCatalogScanPage;
import ai.fabric.governance.catalog.IndexCatalogScanRequest;

import java.util.List;

/**
 * No-op implementation used when catalog is disabled/unavailable.
 */
public class NoopIndexCatalog implements IndexCatalog {
    @Override
    public void upsert(IndexCatalogEntry entry) {
    }

    @Override
    public void delete(String entityType, String entityId) {
    }

    @Override
    public boolean exists(String entityType, String entityId) {
        return false;
    }

    @Override
    public IndexCatalogScanPage scan(IndexCatalogScanRequest request) {
        return IndexCatalogScanPage.builder()
            .entries(List.of())
            .nextCursor(null)
            .hasMore(false)
            .build();
    }
}

