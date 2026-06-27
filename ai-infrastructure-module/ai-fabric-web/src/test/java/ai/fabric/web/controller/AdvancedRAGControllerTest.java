package ai.fabric.web.controller;

import ai.fabric.dto.AdvancedRAGRequest;
import ai.fabric.dto.AdvancedRAGResponse;
import ai.fabric.rag.service.AdvancedRAGService;
import ai.fabric.service.VectorManagementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdvancedRAGControllerTest {

    @Mock
    private AdvancedRAGService advancedRAGService;

    @Mock
    private VectorManagementService vectorManagementService;

    @Test
    void performAdvancedRAGReturnsServiceResponse() {
        AdvancedRAGRequest request = AdvancedRAGRequest.builder()
            .query("How does indexing work?")
            .build();
        AdvancedRAGResponse serviceResponse = AdvancedRAGResponse.builder()
            .query("How does indexing work?")
            .success(true)
            .build();
        when(advancedRAGService.performAdvancedRAG(request)).thenReturn(serviceResponse);

        ResponseEntity<AdvancedRAGResponse> response = controller().performAdvancedRAG(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(serviceResponse);
    }

    @Test
    void performAdvancedRAGBuildsErrorResponseWithSafeQueryFallback() {
        AdvancedRAGRequest request = AdvancedRAGRequest.builder().build();
        when(advancedRAGService.performAdvancedRAG(request)).thenThrow(new IllegalStateException("rag down"));

        ResponseEntity<AdvancedRAGResponse> response = controller().performAdvancedRAG(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getQuery()).isEqualTo("unknown");
        assertThat(response.getBody().getSuccess()).isFalse();
        assertThat(response.getBody().getErrorMessage()).isEqualTo("rag down");
    }

    @Test
    void getAdvancedRAGStatsReturnsRuntimeStatistics() {
        when(advancedRAGService.getStatistics()).thenReturn(Map.of(
            "totalRequests", 2L,
            "successfulRequests", 1L,
            "failedRequests", 1L,
            "successRate", 0.5
        ));

        ResponseEntity<Map<String, Object>> response = controller().getAdvancedRAGStats();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody())
            .containsEntry("totalRequests", 2L)
            .containsEntry("successfulRequests", 1L)
            .containsEntry("failedRequests", 1L)
            .containsEntry("successRate", 0.5)
            .containsEntry("service", "AdvancedRAGService")
            .containsKey("timestamp");
    }

    @Test
    void healthCheckIncludesVectorProviderDiagnosticsWhenAvailable() {
        when(vectorManagementService.getProviderDiagnostics()).thenReturn(Map.of(
            "diagnosticsAvailable", true,
            "providerClass", "ai.fabric.vector.pinecone.PineconeVectorDatabaseService",
            "supportsSearchMetadataFiltering", true,
            "scanFilterMode", "client-side-list-fetch-portable-scalar",
            "readiness", Map.of(
                "status", "READY",
                "operational", true,
                "productionReady", true
            )
        ));

        ResponseEntity<Map<String, Object>> response = controllerWithVectorManagement().healthCheck();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody())
            .containsEntry("status", "UP")
            .containsEntry("service", "AdvancedRAGService")
            .containsKey("timestamp");
        assertThat(response.getBody().get("vectorDatabase"))
            .isInstanceOfSatisfying(Map.class, diagnostics -> assertThat(diagnostics)
                .containsEntry("available", true)
                .containsEntry("diagnosticsAvailable", true)
                .containsEntry("providerClass", "ai.fabric.vector.pinecone.PineconeVectorDatabaseService")
                .containsEntry("supportsSearchMetadataFiltering", true)
                .containsEntry("scanFilterMode", "client-side-list-fetch-portable-scalar")
                .containsKey("readiness"));
        @SuppressWarnings("unchecked")
        Map<String, Object> vectorDiagnostics = (Map<String, Object>) response.getBody().get("vectorDatabase");
        assertThat(vectorDiagnostics.get("readiness"))
            .isInstanceOfSatisfying(Map.class, readiness -> assertThat(readiness)
                .containsEntry("status", "READY")
                .containsEntry("operational", true)
                .containsEntry("productionReady", true));
    }

    @Test
    void healthCheckReportsMissingVectorManagementWithoutFailingController() {
        ResponseEntity<Map<String, Object>> response = controller().healthCheck();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("vectorDatabase"))
            .isInstanceOfSatisfying(Map.class, diagnostics -> assertThat(diagnostics)
                .containsEntry("available", false)
                .containsEntry("diagnosticsAvailable", false)
                .containsEntry("reason", "VectorManagementService bean not available"));
    }

    private AdvancedRAGController controller() {
        return new AdvancedRAGController(advancedRAGService);
    }

    private AdvancedRAGController controllerWithVectorManagement() {
        return new AdvancedRAGController(advancedRAGService, vectorManagementService);
    }
}
