package com.ai.fabric.realapps.agenticresolver.config;

import ai.fabric.access.policy.EntityAccessPolicy;
import ai.fabric.dto.AIAccessSubjectContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DemoAccessControlConfigurationTest {

    private final EntityAccessPolicy policy = new DemoAccessControlConfiguration()
        .accountResolverDemoEntityAccessPolicy();

    @Test
    void grantsResolverRagIntentReadForKnownSubject() {
        assertThat(policy.canAccess(
            AIAccessSubjectContext.builder()
                .subjectId("demo-user")
                .sessionId("demo-session")
                .subjectType("END_USER")
                .build(),
            Map.of(
                "resourceId", "rag:intent",
                "operationType", "READ"
            )
        )).isTrue();
    }

    @Test
    void grantsResolverRagIntentReadForAnonymousSession() {
        assertThat(policy.canAccess(
            AIAccessSubjectContext.builder()
                .sessionId("anon-session")
                .subjectType("ANONYMOUS_SESSION")
                .build(),
            Map.of(
                "resourceId", "rag:intent",
                "operationType", "READ"
            )
        )).isTrue();
    }

    @Test
    void deniesUnknownResourceOrMissingSubject() {
        AIAccessSubjectContext subject = AIAccessSubjectContext.builder()
            .subjectId("demo-user")
            .sessionId("demo-session")
            .build();

        assertThat(policy.canAccess(subject, Map.of(
            "resourceId", "admin:export",
            "operationType", "READ"
        ))).isFalse();

        assertThat(policy.canAccess(AIAccessSubjectContext.builder().build(), Map.of(
            "resourceId", "rag:intent",
            "operationType", "READ"
        ))).isFalse();
    }
}
