package ai.fabric.rag;

import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VectorDatabaseServiceDefaultMethodTest {

    @Test
    void keywordSearchReturnsEmptyResponseWhenProviderDoesNotOverrideIt() {
        VectorDatabaseService service = mock(VectorDatabaseService.class, CALLS_REAL_METHODS);
        AISearchRequest request = AISearchRequest.builder()
            .query("refund window")
            .entityType("policy")
            .limit(5)
            .build();

        AISearchResponse response = service.keywordSearch(null, request);

        assertThat(response.getResults()).isEmpty();
        assertThat(response.getTotalResults()).isZero();
        assertThat(response.getMaxScore()).isZero();
        assertThat(response.getProcessingTimeMs()).isZero();
        assertThat(response.getQuery()).isEqualTo("refund window");
        assertThat(service.supportsKeywordSearch()).isFalse();
    }

    @Test
    void preciseCapabilityDefaultsAreConservativeAndLegacyCompatible() {
        VectorDatabaseService service = mock(VectorDatabaseService.class, CALLS_REAL_METHODS);

        assertThat(service.supportsSearchMetadataFiltering()).isFalse();
        assertThat(service.supportsScanMetadataFiltering()).isFalse();
        assertThat(service.supportsExactFetchById()).isFalse();
        assertThat(service.supportsClearByEntityType()).isFalse();
        assertThat(service.supportsEfficientEntityTypeCount()).isFalse();

        when(service.supportsMetadataFiltering()).thenReturn(true);

        assertThat(service.supportsSearchMetadataFiltering()).isTrue();
        assertThat(service.supportsScanMetadataFiltering()).isTrue();
    }

    @Test
    void adminDiagnosticsExposeStableCapabilityKeys() {
        VectorDatabaseService service = mock(VectorDatabaseService.class, CALLS_REAL_METHODS);

        assertThat(service.adminDiagnostics())
            .containsEntry("providerClass", service.getClass().getName())
            .containsEntry("supportsVectorScan", false)
            .containsEntry("supportsMetadataFiltering", false)
            .containsEntry("supportsSearchMetadataFiltering", false)
            .containsEntry("supportsScanMetadataFiltering", false)
            .containsEntry("supportsExactFetchById", false)
            .containsEntry("supportsClearByEntityType", false)
            .containsEntry("supportsEfficientEntityTypeCount", false)
            .containsEntry("supportsHybridSearch", false)
            .containsEntry("supportsKeywordSearch", false)
            .containsEntry("metadataFilteredSearch", false)
            .containsEntry("metadataFilteredScan", false)
            .containsEntry("countMode", "")
            .containsEntry("clearMode", "")
            .containsEntry("countFallbacks", Map.of())
            .containsEntry("countFallbackReasons", Map.of());

        assertThat(service.adminDiagnostics().get("capabilities"))
            .isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> capabilities = (Map<String, Object>) service.adminDiagnostics().get("capabilities");
        assertThat(capabilities)
            .containsEntry("providerName", service.vectorProviderName())
            .containsEntry("providerClass", service.getClass().getName())
            .containsEntry("supportsVectorScan", false)
            .containsEntry("supportsSearchMetadataFiltering", false)
            .containsEntry("supportsScanMetadataFiltering", false)
            .containsEntry("supportsExactFetchById", false)
            .containsEntry("supportsClearByEntityType", false)
            .containsEntry("supportsEfficientEntityTypeCount", false)
            .containsEntry("entityTypeClearMode", "")
            .containsEntry("lifecycleAdminCompatible", false);
    }

    @Test
    void vectorCapabilitiesUseTypedProviderEvidence() {
        VectorDatabaseService service = mock(VectorDatabaseService.class, CALLS_REAL_METHODS);
        when(service.supportsVectorScan()).thenReturn(true);
        when(service.supportsSearchMetadataFiltering()).thenReturn(true);
        when(service.supportsScanMetadataFiltering()).thenReturn(true);
        when(service.supportsExactFetchById()).thenReturn(true);
        when(service.supportsClearByEntityType()).thenReturn(true);
        when(service.supportsEfficientEntityTypeCount()).thenReturn(true);
        when(service.vectorProviderName()).thenReturn("custom");
        when(service.vectorNativeClient()).thenReturn("native-sdk");
        when(service.vectorSearchFilterMode()).thenReturn("native-search-filter");
        when(service.vectorScanFilterMode()).thenReturn("native-scan-filter");
        when(service.vectorMetadataFilterSubset()).thenReturn("portable-scalar-exact-match");
        when(service.vectorEntityTypeCountMode()).thenReturn("native-count");
        when(service.vectorEntityTypeClearMode()).thenReturn("native-clear");
        when(service.vectorConsistencyModel()).thenReturn("strong");

        assertThat(service.vectorCapabilities().lifecycleAdminCompatible()).isTrue();
        assertThat(service.vectorCapabilities().toMap())
            .containsEntry("providerName", "custom")
            .containsEntry("nativeClient", "native-sdk")
            .containsEntry("searchFilterMode", "native-search-filter")
            .containsEntry("scanFilterMode", "native-scan-filter")
            .containsEntry("metadataFilterSubset", "portable-scalar-exact-match")
            .containsEntry("entityTypeCountMode", "native-count")
            .containsEntry("entityTypeClearMode", "native-clear")
            .containsEntry("consistencyModel", "strong")
            .containsEntry("lifecycleAdminCompatible", true);
    }
}
