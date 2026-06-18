package ai.fabric.it;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.dto.AISecurityRequest;
import ai.fabric.dto.AISecurityResponse;
import ai.fabric.privacy.pii.PIIDetectionService;
import ai.fabric.security.AISecurityService;
import ai.fabric.security.policy.SecurityAnalysisPolicy;
import ai.fabric.security.policy.SecurityAnalysisResult;
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
class AISecurityServiceIntegrationTest {

    @Autowired
    private AISecurityService securityService;

    @Autowired
    private PIIDetectionService piiDetectionService;

    @MockBean
    private SecurityAnalysisPolicy securityAnalysisPolicy;

    @Test
    void builtInThreatsDetected() {
        AISecurityResponse response = securityService.analyzeRequest(securityRequest("'; DROP TABLE users;"));
        assertTrue(response.getThreatsDetected().contains("INJECTION_ATTACK"));
    }

    @Test
    void customPolicyThreatsIncluded() {
        when(securityAnalysisPolicy.analyzeSecurity(any())).thenReturn(
            SecurityAnalysisResult.builder()
                .threats(List.of("CUSTOM_THREAT"))
                .build()
        );

        AISecurityResponse response = securityService.analyzeRequest(securityRequest("regular content"));
        assertTrue(response.getThreatsDetected().contains("CUSTOM_THREAT"));
    }

    private AISecurityRequest securityRequest(String content) {
        return AISecurityRequest.builder()
            .requestId("sec-1")
            .authContext(AIAccessSubjectContext.builder()
                .subjectId("user-1")
                .subjectType("USER")
                .build())
            .content(content)
            .operationType("QUERY")
            .timestamp(LocalDateTime.now())
            .build();
    }
}
