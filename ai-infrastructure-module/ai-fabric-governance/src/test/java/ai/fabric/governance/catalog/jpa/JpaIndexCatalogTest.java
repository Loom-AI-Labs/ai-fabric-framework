package ai.fabric.governance.catalog.jpa;

import ai.fabric.governance.catalog.IndexCatalogScanPage;
import ai.fabric.governance.catalog.IndexCatalogScanRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JpaIndexCatalogTest {

    @Test
    void scanFiltersEntriesByMetadataEquals() {
        IndexCatalogRepository repository = mock(IndexCatalogRepository.class);
        JpaIndexCatalog catalog = new JpaIndexCatalog(repository, new ObjectMapper());

        when(repository.scanByEntityType(eq("doc"), isNull(), org.mockito.ArgumentMatchers.any(Pageable.class)))
            .thenReturn(List.of(
                entity("doc::1", "doc", "1", "vec-1", """
                    {"ownerId":"user-1","dataClassification":"restricted","version":3}
                    """),
                entity("doc::2", "doc", "2", "vec-2", """
                    {"ownerId":"user-2","dataClassification":"restricted","version":3}
                    """),
                entity("doc::3", "doc", "3", "vec-3", "{not-json")
            ));

        IndexCatalogScanPage page = catalog.scan(IndexCatalogScanRequest.builder()
            .entityType("doc")
            .limit(5)
            .metadataEquals(Map.of(
                "ownerId", "user-1",
                "version", "3"
            ))
            .build());

        assertThat(page.getEntries()).hasSize(1);
        assertThat(page.getEntries().getFirst().getEntityId()).isEqualTo("1");
        assertThat(page.isHasMore()).isFalse();
    }

    @Test
    void scanKeepsCursorFromUnfilteredPageWhenMetadataFilterRemovesEntries() {
        IndexCatalogRepository repository = mock(IndexCatalogRepository.class);
        JpaIndexCatalog catalog = new JpaIndexCatalog(repository, new ObjectMapper());

        when(repository.scanByEntityType(eq("doc"), isNull(), org.mockito.ArgumentMatchers.any(Pageable.class)))
            .thenReturn(List.of(
                entity("doc::1", "doc", "1", "vec-1", "{\"ownerId\":\"user-1\"}"),
                entity("doc::2", "doc", "2", "vec-2", "{\"ownerId\":\"user-2\"}"),
                entity("doc::3", "doc", "3", "vec-3", "{\"ownerId\":\"user-1\"}")
            ));

        IndexCatalogScanPage page = catalog.scan(IndexCatalogScanRequest.builder()
            .entityType("doc")
            .limit(2)
            .metadataEquals(Map.of("ownerId", "user-1"))
            .build());

        assertThat(page.getEntries()).extracting("entityId").containsExactly("1");
        assertThat(page.isHasMore()).isTrue();
        assertThat(page.getNextCursor()).isNotBlank();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).scanByEntityType(eq("doc"), isNull(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(3);
    }

    @Test
    void scanReturnsEmptyPageForBlankEntityType() {
        IndexCatalogRepository repository = mock(IndexCatalogRepository.class);
        JpaIndexCatalog catalog = new JpaIndexCatalog(repository, new ObjectMapper());

        IndexCatalogScanPage page = catalog.scan(IndexCatalogScanRequest.builder()
            .entityType(" ")
            .build());

        assertThat(page.getEntries()).isEmpty();
        assertThat(page.isHasMore()).isFalse();
    }

    private static IndexCatalogEntity entity(String key,
                                             String entityType,
                                             String entityId,
                                             String vectorId,
                                             String metadataJson) {
        IndexCatalogEntity entity = new IndexCatalogEntity();
        entity.setKey(key);
        entity.setEntityType(entityType);
        entity.setEntityId(entityId);
        entity.setVectorId(vectorId);
        entity.setIndexedCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        entity.setIndexedUpdatedAt(LocalDateTime.of(2026, 1, Integer.parseInt(entityId), 0, 0));
        entity.setMetadataJson(metadataJson);
        return entity;
    }
}
