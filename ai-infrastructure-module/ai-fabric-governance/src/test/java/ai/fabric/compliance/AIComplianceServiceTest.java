package ai.fabric.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.compliance.policy.ComplianceCheckProvider;
import ai.fabric.compliance.policy.ComplianceCheckResult;
import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.dto.AIComplianceRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class AIComplianceServiceTest {

    @Test
    void checkComplianceUsesCanonicalSubjectIdInResponseAndReport() {
        ComplianceCheckProvider provider = mock(ComplianceCheckProvider.class);
        when(provider.checkCompliance(org.mockito.ArgumentMatchers.any()))
            .thenReturn(ComplianceCheckResult.builder()
                .compliant(true)
                .violations(List.of())
                .build());

        AIComplianceService service = new AIComplianceService(
            Clock.fixed(Instant.parse("2026-01-16T00:00:00Z"), ZoneOffset.UTC),
            provider
        );

        AIComplianceRequest request = AIComplianceRequest.builder()
            .requestId("comp-1")
            .authContext(AIAccessSubjectContext.builder()
                .subjectId("customer-123")
                .sessionId("session-abc")
                .subjectType("END_USER")
                .build())
            .content("review my data processing")
            .timestamp(LocalDateTime.of(2026, 1, 16, 0, 0))
            .build();

        var response = service.checkCompliance(request);

        assertThat(response.getSubjectId()).isEqualTo("customer-123");
        assertThat(response.getReport()).isNotNull();
        assertThat(response.getReport().getSubjectId()).isEqualTo("customer-123");
        assertThat(response.getOverallCompliant()).isTrue();
    }

    @Test
    void checkComplianceFailsClosedWhenProviderReturnsNoDecision() {
        ComplianceCheckProvider provider = mock(ComplianceCheckProvider.class);
        when(provider.checkCompliance(org.mockito.ArgumentMatchers.any())).thenReturn(null);

        AIComplianceService service = new AIComplianceService(
            Clock.fixed(Instant.parse("2026-01-16T00:00:00Z"), ZoneOffset.UTC),
            provider
        );

        AIComplianceRequest request = AIComplianceRequest.builder()
            .requestId("comp-null")
            .authContext(AIAccessSubjectContext.builder()
                .subjectId("customer-123")
                .build())
            .content("review my data processing")
            .build();

        var response = service.checkCompliance(request);

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getOverallCompliant()).isFalse();
        assertThat(response.getViolations()).containsExactly("COMPLIANCE_PROVIDER_EMPTY_RESULT");
        assertThat(response.getErrorMessage()).contains("no decision");
        assertThat(response.getReport().getViolations()).containsExactly("COMPLIANCE_PROVIDER_EMPTY_RESULT");
    }
}
