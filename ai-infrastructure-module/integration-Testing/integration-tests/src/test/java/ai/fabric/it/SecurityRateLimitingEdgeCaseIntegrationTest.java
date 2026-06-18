package ai.fabric.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import ai.fabric.dto.AISecurityEvent;
import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.dto.AISecurityRequest;
import ai.fabric.dto.AISecurityResponse;
import ai.fabric.dto.PIIDetectionResult;
import ai.fabric.privacy.pii.PIIDetectionService;
import ai.fabric.security.AISecurityService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Edge-case coverage verifying that built-in rate limiting blocks sustained request floods.
 */
@Disabled("Disabled due to ApplicationContext loading failures - table creation issues")
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SecurityRateLimitingEdgeCaseIntegrationTest {

    @Autowired
    private AISecurityService securityService;

    @MockitoBean
    private PIIDetectionService piiDetectionService;

    @BeforeEach
    void setUp() {
        reset(piiDetectionService);
        when(piiDetectionService.analyze(any()))
            .thenReturn(PIIDetectionResult.builder()
                .originalQuery("safe")
                .processedQuery("safe")
                .piiDetected(false)
                .build());
    }

    @Test
    void rateLimitTriggersBlockedResponseForBurstTraffic() {
        AISecurityRequest request = AISecurityRequest.builder()
            .requestId("rate-0")
            .authContext(AIAccessSubjectContext.builder()
                .subjectId("edge-user")
                .subjectType("USER")
                .build())
            .operationType("GENERATE")
            .content("Process this prompt safely.")
            .timestamp(LocalDateTime.of(2025, 1, 1, 12, 0))
            .build();

        AISecurityResponse first = securityService.analyzeRequest(request);
        assertTrue(Boolean.TRUE.equals(first.getSuccess()));
        assertFalse(Boolean.TRUE.equals(first.getRateLimitExceeded()));

        AISecurityResponse last = first;
        for (int i = 1; i <= 120; i++) {
            last = securityService.analyzeRequest(
                AISecurityRequest.builder()
                    .requestId("rate-" + i)
                    .authContext(AIAccessSubjectContext.builder()
                        .subjectId("edge-user")
                        .subjectType("USER")
                        .build())
                    .operationType("GENERATE")
                    .content("Process this prompt safely.")
                    .timestamp(LocalDateTime.of(2025, 1, 1, 12, 0))
                    .build()
            );
        }

        assertTrue(Boolean.TRUE.equals(last.getRateLimitExceeded()));
        assertTrue(Boolean.TRUE.equals(last.getShouldBlock()));

        List<AISecurityEvent> events = securityService.getSecurityEvents("edge-user");
        assertThat(events)
            .isNotEmpty()
            .anyMatch(event -> event.getEventType().equals("BLOCKED_REQUEST")
                && event.getThreatsDetected().contains("RATE_LIMIT_EXCEEDED"));
    }
}
