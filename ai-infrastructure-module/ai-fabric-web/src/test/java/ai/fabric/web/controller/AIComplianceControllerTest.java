package ai.fabric.web.controller;

import ai.fabric.compliance.AIComplianceService;
import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.dto.AIComplianceRequest;
import ai.fabric.dto.AIComplianceResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AIComplianceControllerTest {

    @Mock
    private AIComplianceService complianceService;

    @Test
    void checkComplianceReturnsServiceResponse() {
        AIComplianceRequest request = AIComplianceRequest.builder()
            .requestId("req-1")
            .build();
        AIComplianceResponse serviceResponse = AIComplianceResponse.builder()
            .requestId("req-1")
            .subjectId("subject-1")
            .success(true)
            .build();
        when(complianceService.checkCompliance(request)).thenReturn(serviceResponse);

        ResponseEntity<AIComplianceResponse> response = controller().checkCompliance(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(serviceResponse);
    }

    @Test
    void checkComplianceBuildsErrorResponseWithSessionFallback() {
        AIComplianceRequest request = AIComplianceRequest.builder()
            .requestId(" req-2 ")
            .authContext(AIAccessSubjectContext.builder()
                .sessionId(" session-2 ")
                .build())
            .build();
        when(complianceService.checkCompliance(request)).thenThrow(new IllegalStateException("policy down"));

        ResponseEntity<AIComplianceResponse> response = controller().checkCompliance(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getRequestId()).isEqualTo("req-2");
        assertThat(response.getBody().getSubjectId()).isEqualTo("session-2");
        assertThat(response.getBody().getSuccess()).isFalse();
        assertThat(response.getBody().getErrorMessage()).isEqualTo("policy down");
    }

    @Test
    void checkComplianceUsesUnknownFallbacksWhenRequestIdentityIsMissing() {
        AIComplianceRequest request = AIComplianceRequest.builder().build();
        when(complianceService.checkCompliance(request)).thenThrow(new IllegalStateException("policy down"));

        ResponseEntity<AIComplianceResponse> response = controller().checkCompliance(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getRequestId()).isEqualTo("unknown");
        assertThat(response.getBody().getSubjectId()).isEqualTo("unknown");
    }

    private AIComplianceController controller() {
        return new AIComplianceController(complianceService);
    }
}
