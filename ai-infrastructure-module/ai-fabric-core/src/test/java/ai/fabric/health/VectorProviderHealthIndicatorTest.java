package ai.fabric.health;

import ai.fabric.service.VectorManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VectorProviderHealthIndicatorTest {

    @Test
    void reportsUpWhenVectorProviderIsOperational() {
        VectorManagementService vectorManagementService = mock(VectorManagementService.class);
        when(vectorManagementService.getProviderDiagnostics()).thenReturn(Map.of(
            "provider", "pinecone",
            "readiness", Map.of(
                "status", "WARN",
                "operational", true,
                "productionReady", false,
                "reasons", List.of(),
                "warnings", List.of("Pinecone clear consistency waiting is disabled")
            )
        ));

        Health health = new VectorProviderHealthIndicator(vectorManagementService).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
            .containsEntry("readinessStatus", "WARN")
            .containsEntry("productionReady", false)
            .containsEntry("reasons", List.of())
            .containsEntry("warnings", List.of("Pinecone clear consistency waiting is disabled"))
            .containsKey("vectorDatabase");
    }

    @Test
    void reportsDownWhenVectorProviderIsNotReady() {
        VectorManagementService vectorManagementService = mock(VectorManagementService.class);
        when(vectorManagementService.getProviderDiagnostics()).thenReturn(Map.of(
            "diagnosticsAvailable", false,
            "error", "provider down",
            "readiness", Map.of(
                "status", "NOT_READY",
                "operational", false,
                "productionReady", false,
                "reasons", List.of("Vector provider diagnostics are unavailable: provider down"),
                "warnings", List.of()
            )
        ));

        Health health = new VectorProviderHealthIndicator(vectorManagementService).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
            .containsEntry("readinessStatus", "NOT_READY")
            .containsEntry("productionReady", false)
            .containsEntry("reasons", List.of("Vector provider diagnostics are unavailable: provider down"));
    }

    @Test
    void reportsDownWhenReadinessVerdictIsMissing() {
        VectorManagementService vectorManagementService = mock(VectorManagementService.class);
        when(vectorManagementService.getProviderDiagnostics()).thenReturn(Map.of(
            "provider", "custom"
        ));

        Health health = new VectorProviderHealthIndicator(vectorManagementService).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
            .containsEntry("readinessStatus", "NOT_READY")
            .containsEntry("productionReady", false);
        assertThat(health.getDetails().get("reasons"))
            .isEqualTo(List.of("Vector provider readiness verdict is missing."));
    }
}
