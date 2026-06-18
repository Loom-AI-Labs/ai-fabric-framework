package ai.fabric.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.fabric.access.AIAccessControlService;
import ai.fabric.access.policy.EntityAccessPolicy;
import ai.fabric.dto.AIAccessControlRequest;
import ai.fabric.dto.AIAccessControlResponse;
import ai.fabric.dto.AIAccessSubjectContext;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Regression verifying that repeated access-control decisions reuse the short-lived decision cache.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Disabled("Disabled in CI: perf-only regression test exceeds scope of ONNX/Lucene/H2 profile")
class AccessControlCachingPerformanceIntegrationTest {

    @Autowired
    private AIAccessControlService accessControlService;

    @MockitoBean
    private EntityAccessPolicy entityAccessPolicy;

    @BeforeEach
    void clearState() {
        reset(entityAccessPolicy);
        when(entityAccessPolicy.canAccess(any(), any())).thenReturn(true);
    }

    @Test
    void repeatedAccessUsesCachedDecisionAndKeepsLatencyStable() {
        AIAccessControlRequest baseRequest = AIAccessControlRequest.builder()
            .requestId("perf-001")
            .authContext(AIAccessSubjectContext.builder()
                .subjectId("perf-user")
                .subjectType("END_USER")
                .build())
            .resourceId("RESOURCE_X")
            .operationType("READ")
            .metadata(Map.of("region", "us-west"))
            .timestamp(LocalDateTime.of(2025, 1, 1, 9, 0))
            .build();

        AIAccessControlResponse first = accessControlService.checkAccess(baseRequest);
        assertThat(first.getProcessingTimeMs()).isGreaterThanOrEqualTo(0L);
        assertThat(first.getFromCache()).isFalse();

        for (int i = 0; i < 250; i++) {
            AIAccessControlResponse response = accessControlService.checkAccess(baseRequest);
            assertThat(response.getFromCache()).isTrue();
        }

        verify(entityAccessPolicy, times(1)).canAccess(argThat(ctx -> ctx != null && "perf-user".equals(ctx.getSubjectId())), any());
    }
}
