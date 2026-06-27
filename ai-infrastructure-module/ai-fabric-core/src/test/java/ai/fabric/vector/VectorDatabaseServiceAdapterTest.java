package ai.fabric.vector;

import ai.fabric.rag.VectorDatabaseService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VectorDatabaseServiceAdapterTest {

    @Test
    void getInfoPreservesLegacyStatisticsAndAddsAdminDiagnostics() {
        VectorDatabaseService delegate = mock(VectorDatabaseService.class);
        when(delegate.getStatistics()).thenReturn(Map.of(
            "status", "healthy",
            "totalVectors", 12
        ));
        when(delegate.adminDiagnostics()).thenReturn(Map.of(
            "provider", "qdrant",
            "scopePrefix", "customer_a__tenant_b__",
            "supportsSearchMetadataFiltering", true,
            "supportsScanMetadataFiltering", true,
            "metadataFilteredSearch", true,
            "metadataFilteredScan", true
        ));

        VectorDatabaseServiceAdapter adapter = new VectorDatabaseServiceAdapter(delegate);

        Map<String, Object> info = adapter.getInfo();

        assertThat(info)
            .containsEntry("status", "healthy")
            .containsEntry("totalVectors", 12)
            .containsEntry("provider", "qdrant")
            .containsEntry("scopePrefix", "customer_a__tenant_b__")
            .containsEntry("supportsSearchMetadataFiltering", true)
            .containsEntry("supportsScanMetadataFiltering", true)
            .containsEntry("metadataFilteredSearch", true)
            .containsEntry("metadataFilteredScan", true);
        assertThat(info.get("statistics"))
            .isEqualTo(Map.of("status", "healthy", "totalVectors", 12));
        assertThat(info.get("adminDiagnostics"))
            .isEqualTo(Map.of(
                "provider", "qdrant",
                "scopePrefix", "customer_a__tenant_b__",
                "supportsSearchMetadataFiltering", true,
                "supportsScanMetadataFiltering", true,
                "metadataFilteredSearch", true,
                "metadataFilteredScan", true
            ));
    }

    @Test
    void getInfoToleratesNullProviderMaps() {
        VectorDatabaseService delegate = mock(VectorDatabaseService.class);
        when(delegate.getStatistics()).thenReturn(null);
        when(delegate.adminDiagnostics()).thenReturn(null);

        VectorDatabaseServiceAdapter adapter = new VectorDatabaseServiceAdapter(delegate);

        assertThat(adapter.getInfo())
            .containsEntry("statistics", Map.of())
            .containsEntry("adminDiagnostics", Map.of());
    }
}
