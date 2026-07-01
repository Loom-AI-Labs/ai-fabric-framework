package com.ai.fabric.realapps.vectorreadiness.service;

import ai.fabric.rag.VectorDatabaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VectorReadinessServiceTest {

    @Test
    void reportsNotReadyWhenProviderMissing() {
        VectorReadinessService service = new VectorReadinessService(provider(null));

        assertThat(service.readiness().status()).isEqualTo("NOT_READY");
    }

    @Test
    void reportsReadyWhenProviderSupportsLifecycleCapabilities() {
        VectorDatabaseService vector = mock(VectorDatabaseService.class);
        when(vector.adminDiagnostics()).thenReturn(Map.of("provider", "memory"));
        when(vector.supportsExactFetchById()).thenReturn(true);
        when(vector.supportsSearchMetadataFiltering()).thenReturn(true);
        when(vector.supportsClearByEntityType()).thenReturn(true);
        VectorReadinessService service = new VectorReadinessService(provider(vector));

        VectorReadinessService.ReadinessReport report = service.readiness();

        assertThat(report.status()).isEqualTo("READY");
        assertThat(report.diagnostics()).containsEntry("provider", "memory");
    }

    @Test
    void lifecycleRunStoresChecksAndDeletesVector() {
        VectorDatabaseService vector = mock(VectorDatabaseService.class);
        when(vector.storeVector(eq("readiness-document"), eq("readiness-1"), any(), any(), any()))
            .thenReturn("vec-1");
        when(vector.vectorExists("readiness-document", "readiness-1")).thenReturn(true);
        when(vector.removeVector("readiness-document", "readiness-1")).thenReturn(true);
        when(vector.vectorProviderName()).thenReturn("memory");
        when(vector.supportsSearchMetadataFiltering()).thenReturn(true);
        VectorReadinessService service = new VectorReadinessService(provider(vector));

        VectorReadinessService.LifecycleRunResult result = service.runLifecycle();

        assertThat(result.success()).isTrue();
        assertThat(result.evidence()).containsEntry("vectorId", "vec-1");
        assertThat(result.evidence()).containsEntry("removed", true);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<VectorDatabaseService> provider(VectorDatabaseService service) {
        ObjectProvider<VectorDatabaseService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }
}
