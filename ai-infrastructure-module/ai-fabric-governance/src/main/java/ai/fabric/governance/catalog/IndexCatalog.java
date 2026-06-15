package ai.fabric.governance.catalog;

import java.util.List;

/**
 * Inventory/catalog abstraction used by governance features such as retention cleanup and deletion discovery.
 *
 * <p>Implementations can be backed by SQL (catalog table) or vector-native scan/filter capabilities.</p>
 */
public interface IndexCatalog {

    void upsert(IndexCatalogEntry entry);

    void delete(String entityType, String entityId);

    default void deleteByEntityType(String entityType) {
    }

    default void deleteAll() {
    }

    boolean exists(String entityType, String entityId);

    IndexCatalogScanPage scan(IndexCatalogScanRequest request);

    /**
     * Optional helper used for GDPR/CCPA-style deletion discovery when the application cannot enumerate entity ids.
     *
     * <p>Implementations may choose to return an empty list when unsupported.</p>
     */
    default List<IndexCatalogEntry> findByMetadataContainingSnippet(String snippet, int limit) {
        return List.of();
    }
}
