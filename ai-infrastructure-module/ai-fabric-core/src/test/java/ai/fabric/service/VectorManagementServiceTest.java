package ai.fabric.service;

import ai.fabric.dto.VectorRecord;
import ai.fabric.rag.VectorDatabaseService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorManagementServiceTest {

    @Test
    void batchOperationsTreatNullAndEmptyInputsAsNoOp() {
        VectorDatabaseService vectorDatabaseService = mock(VectorDatabaseService.class);
        VectorManagementService service = new VectorManagementService(vectorDatabaseService);

        assertThat(service.batchStoreVectors(null)).isEmpty();
        assertThat(service.batchStoreVectors(List.of())).isEmpty();
        assertThat(service.batchUpdateVectors(null)).isZero();
        assertThat(service.batchUpdateVectors(List.of())).isZero();
        assertThat(service.batchRemoveVectors(null)).isZero();
        assertThat(service.batchRemoveVectors(List.of())).isZero();

        verify(vectorDatabaseService, never()).batchStoreVectors(anyList());
        verify(vectorDatabaseService, never()).batchUpdateVectors(anyList());
        verify(vectorDatabaseService, never()).batchRemoveVectors(anyList());
    }

    @Test
    void batchStoreEnrichesNonNullRecordsWithIndexTimestamps() {
        VectorDatabaseService vectorDatabaseService = mock(VectorDatabaseService.class);
        when(vectorDatabaseService.batchStoreVectors(anyList())).thenReturn(List.of("vec-1"));
        VectorManagementService service = new VectorManagementService(vectorDatabaseService);

        List<String> vectorIds = service.batchStoreVectors(List.of(
            VectorRecord.builder()
                .entityType("product")
                .entityId("p-1")
                .content("Product content")
                .embedding(List.of(0.1d, 0.2d))
                .metadata(Map.of("category", "outerwear"))
                .build()
        ));

        assertThat(vectorIds).containsExactly("vec-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VectorRecord>> recordsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorDatabaseService).batchStoreVectors(recordsCaptor.capture());
        assertThat(recordsCaptor.getValue()).hasSize(1);
        assertThat(recordsCaptor.getValue().getFirst().getMetadata())
            .containsEntry("category", "outerwear")
            .containsKeys("_indexedCreatedAt", "_indexedUpdatedAt");
    }

    @Test
    void providerDiagnosticsExposeStableVectorProviderEvidence() {
        VectorDatabaseService vectorDatabaseService = mock(VectorDatabaseService.class);
        when(vectorDatabaseService.adminDiagnostics()).thenReturn(Map.ofEntries(
            Map.entry("providerClass", "ai.fabric.vector.qdrant.QdrantVectorDatabaseService"),
            Map.entry("provider", "qdrant"),
            Map.entry("supportsVectorScan", true),
            Map.entry("supportsSearchMetadataFiltering", true),
            Map.entry("supportsScanMetadataFiltering", true),
            Map.entry("supportsExactFetchById", true),
            Map.entry("supportsClearByEntityType", true),
            Map.entry("supportsEfficientEntityTypeCount", true),
            Map.entry("failOnMissingPayloadIndex", true),
            Map.entry("searchFilterMode", "qdrant-payload-filter-with-client-side-fallback"),
            Map.entry("scanFilterMode", "qdrant-payload-filter"),
            Map.entry("countMode", "qdrant-scroll-count"),
            Map.entry("clearMode", "qdrant-filtered-delete")
        ));
        VectorManagementService service = new VectorManagementService(vectorDatabaseService);

        Map<String, Object> diagnostics = service.getProviderDiagnostics();

        assertThat(diagnostics)
            .containsEntry("diagnosticsAvailable", true)
            .containsEntry("providerClass", "ai.fabric.vector.qdrant.QdrantVectorDatabaseService")
            .containsEntry("supportsSearchMetadataFiltering", true)
            .containsEntry("supportsScanMetadataFiltering", true)
            .containsEntry("searchFilterMode", "qdrant-payload-filter-with-client-side-fallback")
            .containsKey("readiness");
        assertThat(diagnostics.get("readiness"))
            .isInstanceOfSatisfying(Map.class, readiness -> assertThat(readiness)
                .containsEntry("status", "READY")
                .containsEntry("operational", true)
                .containsEntry("productionReady", true));
        verify(vectorDatabaseService).adminDiagnostics();
        verify(vectorDatabaseService, never()).getStatistics();
    }

    @Test
    void providerDiagnosticsFailClosedWithoutThrowingFromHealthSurface() {
        VectorDatabaseService vectorDatabaseService = mock(VectorDatabaseService.class);
        when(vectorDatabaseService.adminDiagnostics()).thenThrow(new IllegalStateException("provider down"));
        VectorManagementService service = new VectorManagementService(vectorDatabaseService);

        Map<String, Object> diagnostics = service.getProviderDiagnostics();

        assertThat(diagnostics)
            .containsEntry("diagnosticsAvailable", false)
            .containsEntry("error", "provider down")
            .containsKey("readiness");
        assertThat(diagnostics.get("readiness"))
            .isInstanceOfSatisfying(Map.class, readiness -> assertThat(readiness)
                .containsEntry("status", "NOT_READY")
                .containsEntry("operational", false)
                .containsEntry("productionReady", false));
    }
}
