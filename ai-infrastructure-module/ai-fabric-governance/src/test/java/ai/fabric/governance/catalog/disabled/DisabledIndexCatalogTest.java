package ai.fabric.governance.catalog.disabled;

import ai.fabric.governance.catalog.IndexCatalogEntry;
import ai.fabric.governance.catalog.IndexCatalogScanRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DisabledIndexCatalogTest {

    @Test
    void disabledCatalogAcceptsWritesButDoesNotPersistEntries() {
        DisabledIndexCatalog catalog = new DisabledIndexCatalog();
        IndexCatalogEntry entry = IndexCatalogEntry.builder()
            .entityType("product")
            .entityId("p-1")
            .vectorId("vec-1")
            .build();

        catalog.upsert(entry);
        catalog.delete("product", "p-1");

        assertThat(catalog.exists("product", "p-1")).isFalse();
        assertThat(catalog.scan(IndexCatalogScanRequest.builder()
            .entityType("product")
            .limit(20)
            .build()))
            .satisfies(page -> {
                assertThat(page.getEntries()).isEmpty();
                assertThat(page.isHasMore()).isFalse();
                assertThat(page.getNextCursor()).isNull();
            });
    }
}
