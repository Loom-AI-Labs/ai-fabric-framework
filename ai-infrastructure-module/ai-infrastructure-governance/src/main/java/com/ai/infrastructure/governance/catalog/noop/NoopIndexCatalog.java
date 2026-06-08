package com.ai.infrastructure.governance.catalog.noop;

import com.ai.infrastructure.governance.catalog.IndexCatalog;
import com.ai.infrastructure.governance.catalog.IndexCatalogEntry;
import com.ai.infrastructure.governance.catalog.IndexCatalogScanPage;
import com.ai.infrastructure.governance.catalog.IndexCatalogScanRequest;

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

