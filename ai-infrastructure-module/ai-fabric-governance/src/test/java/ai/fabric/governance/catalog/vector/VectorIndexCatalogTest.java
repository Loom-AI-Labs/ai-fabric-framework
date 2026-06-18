package ai.fabric.governance.catalog.vector;

import ai.fabric.dto.VectorRecord;
import ai.fabric.dto.VectorScanPage;
import ai.fabric.dto.VectorScanRequest;
import ai.fabric.governance.catalog.IndexCatalogScanPage;
import ai.fabric.governance.catalog.IndexCatalogScanRequest;
import ai.fabric.rag.VectorDatabaseService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VectorIndexCatalogTest {

    @Test
    void scanMapsVectorRecordsAndRequestsLightweightPayload() {
        VectorDatabaseService vectorDatabaseService = mock(VectorDatabaseService.class);
        when(vectorDatabaseService.scan(org.mockito.ArgumentMatchers.any(VectorScanRequest.class)))
            .thenReturn(VectorScanPage.builder()
                .vectors(List.of(VectorRecord.builder()
                    .vectorId("vec-1")
                    .entityType("doc")
                    .entityId("1")
                    .metadata(Map.of("ownerId", "user-1"))
                    .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                    .updatedAt(LocalDateTime.of(2026, 1, 2, 0, 0))
                    .build()))
                .nextCursor("next")
                .hasMore(true)
                .build());

        VectorIndexCatalog catalog = new VectorIndexCatalog(vectorDatabaseService);

        IndexCatalogScanPage page = catalog.scan(IndexCatalogScanRequest.builder()
            .entityType("doc")
            .metadataEquals(Map.of("ownerId", "user-1"))
            .limit(25)
            .cursor("cursor")
            .build());

        assertThat(page.getEntries()).hasSize(1);
        assertThat(page.getEntries().getFirst().getEntityId()).isEqualTo("1");
        assertThat(page.getEntries().getFirst().getMetadata()).containsEntry("ownerId", "user-1");
        assertThat(page.getNextCursor()).isEqualTo("next");
        assertThat(page.isHasMore()).isTrue();

        ArgumentCaptor<VectorScanRequest> requestCaptor = ArgumentCaptor.forClass(VectorScanRequest.class);
        verify(vectorDatabaseService).scan(requestCaptor.capture());
        VectorScanRequest request = requestCaptor.getValue();
        assertThat(request.getEntityType()).isEqualTo("doc");
        assertThat(request.getLimit()).isEqualTo(25);
        assertThat(request.getCursor()).isEqualTo("cursor");
        assertThat(request.isIncludeContent()).isFalse();
        assertThat(request.isIncludeEmbedding()).isFalse();
        assertThat(request.isIncludeMetadata()).isTrue();
    }

    @Test
    void scanReturnsEmptyPageForBlankRequestWithoutCallingVectorService() {
        VectorDatabaseService vectorDatabaseService = mock(VectorDatabaseService.class);
        VectorIndexCatalog catalog = new VectorIndexCatalog(vectorDatabaseService);

        IndexCatalogScanPage page = catalog.scan(IndexCatalogScanRequest.builder()
            .entityType(" ")
            .build());

        assertThat(page.getEntries()).isEmpty();
        assertThat(page.isHasMore()).isFalse();
        verifyNoInteractions(vectorDatabaseService);
    }

    @Test
    void bulkDeletesDelegateToVectorDatabaseService() {
        VectorDatabaseService vectorDatabaseService = mock(VectorDatabaseService.class);
        VectorIndexCatalog catalog = new VectorIndexCatalog(vectorDatabaseService);

        catalog.deleteByEntityType("doc");
        catalog.deleteAll();

        verify(vectorDatabaseService).clearVectorsByEntityType("doc");
        verify(vectorDatabaseService).clearVectors();
    }
}
