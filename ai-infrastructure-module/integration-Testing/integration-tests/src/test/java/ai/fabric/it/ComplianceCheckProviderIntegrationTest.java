package ai.fabric.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.fabric.compliance.AIComplianceService;
import ai.fabric.compliance.policy.ComplianceCheckProvider;
import ai.fabric.compliance.policy.ComplianceCheckResult;
import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.dto.AIComplianceRequest;
import ai.fabric.dto.AIComplianceResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Integration coverage for {@link ComplianceCheckProvider} hook as described in the
 * infrastructure integration test blueprint.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "ai.governance.enabled=true",
    "ai.governance.compliance.enabled=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ComplianceCheckProviderIntegrationTest {

    @Autowired
    private AIComplianceService complianceService;

    @Autowired
    private Clock clock;

    @MockitoBean
    private ComplianceCheckProvider complianceCheckProvider;

    @BeforeEach
    void setUp() {
        reset(complianceCheckProvider);
    }

    @Test
    void providerViolationsArePropagated() {
        when(complianceCheckProvider.checkCompliance(any()))
            .thenReturn(ComplianceCheckResult.builder()
                .compliant(false)
                .violations(List.of("GDPR_ARTICLE_5", "PCI_DSS"))
                .details("Shared data outside approved region")
                .build());

        AIComplianceResponse response = complianceService.checkCompliance(baseRequest("req-violation"));

        assertFalse(Boolean.TRUE.equals(response.getOverallCompliant()));
        assertThat(response.getViolations()).contains("GDPR_ARTICLE_5", "PCI_DSS");
        assertThat(response.getReport()).isNotNull();
        assertThat(response.getReport().getNotes()).isEqualTo("Shared data outside approved region");
        verify(complianceCheckProvider, times(1)).checkCompliance(any());
    }

    @Test
    void failsWhenProviderMissing() {
        AIComplianceService serviceWithoutProvider = new AIComplianceService(clock, null);

        assertThatThrownBy(() -> serviceWithoutProvider.checkCompliance(baseRequest("req-default")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No ComplianceCheckProvider bean available");
    }

    @Test
    void providerExceptionRegistersComplianceError() {
        when(complianceCheckProvider.checkCompliance(any()))
            .thenThrow(new IllegalStateException("policy backend offline"));

        AIComplianceResponse response = complianceService.checkCompliance(baseRequest("req-ex"));

        assertFalse(Boolean.TRUE.equals(response.getOverallCompliant()));
        assertThat(response.getViolations()).contains("COMPLIANCE_PROVIDER_ERROR");
        assertFalse(Boolean.TRUE.equals(response.getSuccess()));
        assertThat(response.getErrorMessage()).contains("policy backend offline");
    }

    private AIComplianceRequest baseRequest(String requestId) {
        return AIComplianceRequest.builder()
            .requestId(requestId)
            .authContext(AIAccessSubjectContext.builder()
                .subjectId("user-789")
                .subjectType("USER")
                .build())
            .content("process payroll export")
            .dataClassification("CONFIDENTIAL")
            .regulationTypes(List.of("GDPR", "CCPA"))
            .metadata(Map.of("region", "eu"))
            .timestamp(LocalDateTime.of(2025, 1, 1, 13, 0))
            .build();
    }
}
