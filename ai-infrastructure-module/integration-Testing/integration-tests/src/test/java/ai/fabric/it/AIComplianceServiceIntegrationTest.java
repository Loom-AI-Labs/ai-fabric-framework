package ai.fabric.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ai.fabric.compliance.AIComplianceService;
import ai.fabric.compliance.policy.ComplianceCheckProvider;
import ai.fabric.compliance.policy.ComplianceCheckResult;
import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.dto.AIComplianceRequest;
import ai.fabric.dto.AIComplianceResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "ai.governance.enabled=true",
    "ai.governance.compliance.enabled=true"
})
class AIComplianceServiceIntegrationTest {

    @Autowired
    private AIComplianceService complianceService;

    @MockitoBean
    private ComplianceCheckProvider complianceCheckProvider;

    @Test
    void hookResultIsApplied() {
        when(complianceCheckProvider.checkCompliance(any())).thenReturn(
            ComplianceCheckResult.builder()
                .compliant(false)
                .violations(List.of("GDPR_VIOLATION"))
                .build()
        );

        AIComplianceResponse response = complianceService.checkCompliance(request("user-1", "content"));

        assertFalse(Boolean.TRUE.equals(response.getOverallCompliant()));
        assertEquals(List.of("GDPR_VIOLATION"), response.getViolations());
    }

    @Test
    void failClosedWhenHookReturnsNull() {
        when(complianceCheckProvider.checkCompliance(any())).thenReturn(null);

        AIComplianceResponse response = complianceService.checkCompliance(request("user-2", "content"));

        assertFalse(Boolean.TRUE.equals(response.getOverallCompliant()));
        assertFalse(Boolean.TRUE.equals(response.getSuccess()));
        assertEquals(List.of("COMPLIANCE_PROVIDER_EMPTY_RESULT"), response.getViolations());
    }

    private AIComplianceRequest request(String userId, String content) {
        return AIComplianceRequest.builder()
            .requestId("comp-" + userId)
            .authContext(AIAccessSubjectContext.builder()
                .subjectId(userId)
                .subjectType("USER")
                .build())
            .content(content)
            .timestamp(LocalDateTime.now())
            .build();
    }
}
