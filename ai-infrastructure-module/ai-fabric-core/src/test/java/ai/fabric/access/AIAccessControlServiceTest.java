package ai.fabric.access;

import ai.fabric.cache.AICacheNames;
import ai.fabric.access.policy.EntityAccessPolicy;
import ai.fabric.dto.AIAccessControlRequest;
import ai.fabric.dto.AIAccessControlResponse;
import ai.fabric.dto.AIAccessSubjectContext;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

class AIAccessControlServiceTest {

    private final Clock clock = Clock.systemUTC();

    @Test
    void throwsWhenPolicyMissing() {
        AIAccessControlService service = new AIAccessControlService(clock, null);

        assertThatThrownBy(() -> service.checkAccess(buildRequest("user-1")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No EntityAccessPolicy bean available");
    }

    @Test
    void deniesAccessWhenPolicyThrowsError() {
        EntityAccessPolicy policy = mock(EntityAccessPolicy.class);
        doThrow(new IllegalStateException("boom"))
            .when(policy).canAccess(any(), any());

        AIAccessControlService service = new AIAccessControlService(clock, policy);

        AIAccessControlResponse response = service.checkAccess(buildRequest("user-2"));

        assertThat(response.getAccessGranted()).isFalse();
        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorMessage()).contains("boom");
    }

    @Test
    void shouldAllowAnonymousSessionIdWhenUserIdMissing() {
        EntityAccessPolicy policy = mock(EntityAccessPolicy.class);
        doReturn(true).when(policy).canAccess(any(), any());

        AIAccessControlService service = new AIAccessControlService(clock, policy);

        AIAccessControlResponse response = service.checkAccess(AIAccessControlRequest.builder()
            .requestId("req-session-1")
            .authContext(AIAccessSubjectContext.builder()
                .subjectId("session-1")
                .sessionId("session-1")
                .subjectType("ANONYMOUS_SESSION")
                .build())
            .resourceId("resource-1")
            .operationType("READ")
            .metadata(Map.of("test", true))
            .build());

        assertThat(response.getAccessGranted()).isTrue();
        assertThat(response.getSubjectId()).isEqualTo("session-1");
    }

    @Test
    void cachesSuccessfulDecisionsAcrossEquivalentRequests() {
        EntityAccessPolicy policy = mock(EntityAccessPolicy.class);
        doReturn(true).when(policy).canAccess(any(), any());

        AIAccessControlService service = new AIAccessControlService(
            clock,
            policy,
            new ConcurrentMapCacheManager(AICacheNames.ACCESS_DECISIONS)
        );

        AIAccessControlResponse first = service.checkAccess(buildRequest("cache-user"));
        AIAccessControlResponse second = service.checkAccess(buildRequest("cache-user"));

        assertThat(first.getFromCache()).isFalse();
        assertThat(second.getFromCache()).isTrue();
        verify(policy, times(1)).canAccess(any(), any());
    }

    @Test
    void doesNotCacheHookFailures() {
        EntityAccessPolicy policy = mock(EntityAccessPolicy.class);
        doThrow(new IllegalStateException("timeout"))
            .when(policy).canAccess(any(), any());

        AIAccessControlService service = new AIAccessControlService(
            clock,
            policy,
            new ConcurrentMapCacheManager(AICacheNames.ACCESS_DECISIONS)
        );

        AIAccessControlResponse first = service.checkAccess(buildRequest("failure-user"));
        AIAccessControlResponse second = service.checkAccess(buildRequest("failure-user"));

        assertThat(first.getFromCache()).isFalse();
        assertThat(second.getFromCache()).isFalse();
        verify(policy, times(2)).canAccess(any(), any());
    }

    @Test
    void normalizesMetadataAndUserAttributesBeforePolicyEvaluation() {
        EntityAccessPolicy policy = mock(EntityAccessPolicy.class);
        doReturn(true).when(policy).canAccess(any(), any());
        AIAccessControlService service = new AIAccessControlService(clock, policy);

        AIAccessControlRequest request = buildRequest("normalize-user");
        request.setMetadata(new java.util.LinkedHashMap<>());
        request.getMetadata().put(null, "ignored-key");
        request.getMetadata().put("blank", "   ");
        request.getMetadata().put("role", " admin ");
        request.getMetadata().put("nested", Map.of("team", " support ", "empty", ""));
        request.setUserAttributes(new java.util.LinkedHashMap<>());
        request.getUserAttributes().put(null, "ignored-key");
        request.getUserAttributes().put("tier", " gold ");

        AIAccessControlResponse response = service.checkAccess(request);

        assertThat(response.getAccessGranted()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> entityContext = ArgumentCaptor.forClass(Map.class);
        verify(policy).canAccess(same(request.getAuthContext()), entityContext.capture());

        assertThat(entityContext.getValue().get("metadata"))
            .isEqualTo(Map.of(
                "role", "admin",
                "nested", Map.of("team", "support")
            ));
        assertThat(entityContext.getValue().get("userAttributes"))
            .isEqualTo(Map.of("tier", "gold"));
    }

    private AIAccessControlRequest buildRequest(String userId) {
        return AIAccessControlRequest.builder()
            .requestId("req-" + userId)
            .authContext(AIAccessSubjectContext.builder()
                .subjectId(userId)
                .subjectType("END_USER")
                .build())
            .resourceId("resource-" + userId)
            .operationType("READ")
            .metadata(Map.of("test", true))
            .build();
    }
}
