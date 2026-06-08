package com.ai.fabric.runtime.authz;

import com.ai.infrastructure.dto.AIAccessSubjectContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AllowVerifiedEntityAccessPolicyTest {

    private final AllowVerifiedEntityAccessPolicy policy = new AllowVerifiedEntityAccessPolicy();

    @Test
    void grantsVerifiedNonAnonymousSubject() {
        AIAccessSubjectContext authContext = AIAccessSubjectContext.builder()
            .subjectId("user-1")
            .subjectType("END_USER")
            .authMode("PLATFORM_PROXY_SESSION")
            .build();

        assertThat(policy.canAccess(authContext, Map.of("resourceId", "rag:intent"))).isTrue();
    }

    @Test
    void deniesAnonymousSubject() {
        AIAccessSubjectContext authContext = AIAccessSubjectContext.builder()
            .subjectId("session-1")
            .subjectType("ANONYMOUS_SESSION")
            .authMode("PUBLIC_RUNTIME_ANONYMOUS")
            .build();

        assertThat(policy.canAccess(authContext, Map.of("resourceId", "rag:intent"))).isFalse();
    }

    @Test
    void deniesMissingVerifiedContextMetadata() {
        AIAccessSubjectContext authContext = AIAccessSubjectContext.builder()
            .subjectId("user-1")
            .subjectType("END_USER")
            .build();

        assertThat(policy.canAccess(authContext, Map.of("resourceId", "rag:intent"))).isFalse();
    }
}
