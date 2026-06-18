package ai.fabric.web.controller;

import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.dto.AISecurityRequest;
import ai.fabric.dto.AISecurityResponse;
import ai.fabric.security.AISecurityService;
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
class AISecurityControllerTest {

    @Mock
    private AISecurityService securityService;

    @Test
    void analyzeSecurityReturnsServiceResponse() {
        AISecurityRequest request = AISecurityRequest.builder()
            .requestId("req-1")
            .authContext(AIAccessSubjectContext.builder()
                .subjectId("subject-1")
                .build())
            .build();
        AISecurityResponse serviceResponse = AISecurityResponse.builder()
            .requestId("req-1")
            .subjectId("subject-1")
            .success(true)
            .build();
        when(securityService.analyzeRequest(request)).thenReturn(serviceResponse);

        ResponseEntity<AISecurityResponse> response = controller().analyzeSecurity(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(serviceResponse);
    }

    @Test
    void analyzeSecurityBuildsErrorResponseWithSessionFallback() {
        AISecurityRequest request = AISecurityRequest.builder()
            .requestId(" req-2 ")
            .authContext(AIAccessSubjectContext.builder()
                .sessionId(" session-2 ")
                .build())
            .build();
        when(securityService.analyzeRequest(request)).thenThrow(new IllegalStateException("security down"));

        ResponseEntity<AISecurityResponse> response = controller().analyzeSecurity(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getRequestId()).isEqualTo("req-2");
        assertThat(response.getBody().getSubjectId()).isEqualTo("session-2");
        assertThat(response.getBody().getSuccess()).isFalse();
        assertThat(response.getBody().getErrorMessage()).isEqualTo("security down");
    }

    @Test
    void getSecurityStatisticsReturnsServiceStatistics() {
        Map<String, Object> stats = Map.of(
            "totalEvents", 2L,
            "blockedEvents", 1L
        );
        when(securityService.getSecurityStatistics()).thenReturn(stats);

        ResponseEntity<Map<String, Object>> response = controller().getSecurityStatistics();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(stats);
    }

    private AISecurityController controller() {
        return new AISecurityController(securityService);
    }
}
