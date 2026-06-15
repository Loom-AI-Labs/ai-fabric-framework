package ai.fabric.intent.orchestration.pipeline.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.fabric.compliance.AIComplianceService;
import ai.fabric.dto.AIComplianceRequest;
import ai.fabric.dto.AIComplianceResponse;
import ai.fabric.intent.orchestration.OrchestrationContextMetadataKeys;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ComplianceCheckStepTest {

    @Mock
    private AIComplianceService complianceService;

    @Captor
    private ArgumentCaptor<AIComplianceRequest> requestCaptor;

    private ComplianceCheckStep step;

    @BeforeEach
    void setUp() {
        step = new ComplianceCheckStep(complianceService);
    }

    @Test
    void processPassesCanonicalAuthContextToComplianceService() {
        when(complianceService.checkCompliance(any()))
            .thenReturn(AIComplianceResponse.builder()
                .overallCompliant(true)
                .build());

        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user-123")
            .sessionId("session-456")
            .metadata(Map.of(
                OrchestrationContextMetadataKeys.SUBJECT_ID, "user-123",
                OrchestrationContextMetadataKeys.SUBJECT_TYPE, "END_USER",
                OrchestrationContextMetadataKeys.AUTH_MODE, "PLATFORM_PRIVATE"))
            .build();

        PipelineContext context = PipelineContext.from("Review my account", orchestrationContext);

        PipelineContext result = step.process(context);

        assertThat(result.isShouldTerminate()).isFalse();
        verify(complianceService).checkCompliance(requestCaptor.capture());
        AIComplianceRequest request = requestCaptor.getValue();
        assertThat(request.getAuthContext()).isNotNull();
        assertThat(request.getAuthContext().getSubjectId()).isEqualTo("user-123");
        assertThat(request.getAuthContext().getSessionId()).isEqualTo("session-456");
        assertThat(request.getContent()).isEqualTo("Review my account");
    }

    @Test
    void processTerminatesWhenComplianceFails() {
        when(complianceService.checkCompliance(any()))
            .thenReturn(AIComplianceResponse.builder()
                .overallCompliant(false)
                .build());

        PipelineContext context = PipelineContext.from("Export customer data", OrchestrationContext.forUser("user-1"));

        PipelineContext result = step.process(context);

        assertThat(result.isShouldTerminate()).isTrue();
        assertThat(result.getEarlyTerminationResult()).isNotNull();
        assertThat(result.getEarlyTerminationResult().getMessage())
            .isEqualTo("Request failed compliance validation.");
    }
}
