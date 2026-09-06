package com.ai.fabric.realapps.behavior.config;

import ai.fabric.access.policy.EntityAccessPolicy;
import ai.fabric.dto.AIAccessSubjectContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BehaviorAccessControlConfigurationTest {

    private final EntityAccessPolicy policy = new BehaviorAccessControlConfiguration()
        .behaviorAnalysisAccessPolicy();

    @Test
    void allowsOnlyTheTrustedBehaviorSpecialistReadBoundary() {
        AIAccessSubjectContext trusted = AIAccessSubjectContext.builder()
            .subjectId("behavior-user-1")
            .authMode("TRUSTED_APPLICATION")
            .callerType("SERVICE")
            .tenantId("behavior-demo")
            .grantedScopes(List.of("specialist:behavior-risk-analyst@1"))
            .build();

        assertThat(policy.canAccess(trusted, Map.of(
            "resourceId", "rag:intent",
            "operationType", "READ"
        ))).isTrue();
        AIAccessSubjectContext scheduled = AIAccessSubjectContext.builder()
            .subjectId("behavior-user-1")
            .authMode("TRUSTED_SCHEDULED")
            .callerType("SYSTEM")
            .tenantId("behavior-demo")
            .grantedScopes(List.of("specialist:behavior-risk-analyst@1"))
            .build();
        assertThat(policy.canAccess(scheduled, Map.of(
            "resourceId", "rag:intent",
            "operationType", "READ"
        ))).isTrue();
        assertThat(policy.canAccess(trusted, Map.of(
            "resourceId", "rag:intent",
            "operationType", "WRITE"
        ))).isFalse();
    }

    @Test
    void deniesMissingScopeAndUntrustedCallers() {
        AIAccessSubjectContext missingScope = AIAccessSubjectContext.builder()
            .subjectId("behavior-user-1")
            .authMode("TRUSTED_APPLICATION")
            .callerType("SERVICE")
            .tenantId("behavior-demo")
            .grantedScopes(List.of())
            .build();
        AIAccessSubjectContext interactive = AIAccessSubjectContext.builder()
            .subjectId("behavior-user-1")
            .authMode("SESSION")
            .callerType("END_USER")
            .tenantId("behavior-demo")
            .grantedScopes(List.of("specialist:behavior-risk-analyst@1"))
            .build();
        AIAccessSubjectContext mismatchedMachineTrust = AIAccessSubjectContext.builder()
            .subjectId("behavior-user-1")
            .authMode("TRUSTED_APPLICATION")
            .callerType("SYSTEM")
            .tenantId("behavior-demo")
            .grantedScopes(List.of("specialist:behavior-risk-analyst@1"))
            .build();

        Map<String, Object> read = Map.of("resourceId", "rag:intent", "operationType", "READ");
        assertThat(policy.canAccess(missingScope, read)).isFalse();
        assertThat(policy.canAccess(interactive, read)).isFalse();
        assertThat(policy.canAccess(mismatchedMachineTrust, read)).isFalse();
    }
}
