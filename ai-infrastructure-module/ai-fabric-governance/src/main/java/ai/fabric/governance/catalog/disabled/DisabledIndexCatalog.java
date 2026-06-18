package ai.fabric.governance.catalog.disabled;

import ai.fabric.governance.catalog.IndexCatalog;
import ai.fabric.governance.catalog.IndexCatalogEntry;
import ai.fabric.governance.catalog.IndexCatalogScanPage;
import ai.fabric.governance.catalog.IndexCatalogScanRequest;

import java.util.List;

/**
 * Catalog implementation used when governance index cataloging is explicitly disabled
 * or no durable catalog backend is available in AUTO mode.
 */
public class DisabledIndexCatalog implements IndexCatalog {
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
