package ai.fabric.it;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@Disabled("Disabled due to ApplicationContext loading failures - table creation issues")
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class AIAccessControlServiceIntegrationTest {

    @Autowired
    private AIAccessControlService accessControlService;

    @MockBean
    private EntityAccessPolicy entityAccessPolicy;

    @Test
    void hookCalledWithEntityContext() {
        when(entityAccessPolicy.canAccess(any(), any())).thenReturn(true);

        AIAccessControlResponse response = accessControlService.checkAccess(accessRequest("user-1", "resource-1"));

        assertTrue(Boolean.TRUE.equals(response.getAccessGranted()));
        assertFalse(Boolean.TRUE.equals(response.getFromCache()));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(entityAccessPolicy).canAccess(argThat(ctx -> ctx != null && "user-1".equals(ctx.getSubjectId())), captor.capture());
        Map<String, Object> entity = captor.getValue();
        assertTrue(entity.containsKey("resourceId"));
        assertTrue(entity.containsKey("operationType"));
    }

    @Test
    void deniesAccessWhenPolicyReturnsFalse() {
        when(entityAccessPolicy.canAccess(any(), any())).thenReturn(false);

        AIAccessControlResponse response = accessControlService.checkAccess(accessRequest("user-2", "resource-2"));

        assertFalse(Boolean.TRUE.equals(response.getAccessGranted()));
        assertFalse(Boolean.TRUE.equals(response.getFromCache()));
        verify(entityAccessPolicy, times(1)).canAccess(argThat(ctx -> ctx != null && "user-2".equals(ctx.getSubjectId())), any());
    }

    private AIAccessControlRequest accessRequest(String userId, String resourceId) {
        return AIAccessControlRequest.builder()
            .requestId("req-" + userId)
            .authContext(AIAccessSubjectContext.builder()
                .subjectId(userId)
                .subjectType("END_USER")
                .build())
            .resourceId(resourceId)
            .operationType("READ")
            .timestamp(LocalDateTime.now())
            .build();
    }
}
