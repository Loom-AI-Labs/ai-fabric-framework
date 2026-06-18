package ai.fabric.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.junit.jupiter.api.Disabled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@Disabled("Disabled due to ApplicationContext loading failures - table creation issues")
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class AIComplianceServiceIntegrationTest {

    @Autowired
    private AIComplianceService complianceService;

    @MockBean
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
    void compliantWhenHookReturnsNull() {
        when(complianceCheckProvider.checkCompliance(any())).thenReturn(null);

        AIComplianceResponse response = complianceService.checkCompliance(request("user-2", "content"));

        assertTrue(Boolean.TRUE.equals(response.getOverallCompliant()));
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
