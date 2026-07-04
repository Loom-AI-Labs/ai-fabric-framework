package com.ai.fabric.realapps.chat.config;

import ai.fabric.access.policy.EntityAccessPolicy;
import ai.fabric.dto.AIAccessSubjectContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DemoAccessAndComplianceConfigurationTest {

    private final EntityAccessPolicy policy = new DemoAccessAndComplianceConfiguration().demoEntityAccessPolicy();

    @Test
    void allowsKnownDemoResourcesForIdentifiedSubject() {
        AIAccessSubjectContext subject = AIAccessSubjectContext.builder()
            .subjectId("shopping-demo-user-browser")
            .sessionId("shopping-demo-session-browser")
            .build();

        assertThat(policy.canAccess(subject, Map.of(
            "resourceId", "product",
            "operationType", "read"
        ))).isTrue();
        assertThat(policy.canAccess(subject, Map.of(
            "resourceId", "vectorSpace:policy",
            "operationType", "search"
        ))).isTrue();
    }

    @Test
    void deniesAnonymousUnknownResourcesAndUnknownOperations() {
        AIAccessSubjectContext subject = AIAccessSubjectContext.builder()
            .subjectId("shopping-demo-user-browser")
            .build();

        assertThat(policy.canAccess(null, Map.of(
            "resourceId", "product",
            "operationType", "read"
        ))).isFalse();
        assertThat(policy.canAccess(AIAccessSubjectContext.builder().build(), Map.of(
            "resourceId", "product",
            "operationType", "read"
        ))).isFalse();
        assertThat(policy.canAccess(subject, Map.of(
            "resourceId", "payroll",
            "operationType", "read"
        ))).isFalse();
        assertThat(policy.canAccess(subject, Map.of(
            "resourceId", "product",
            "operationType", "adminOverride"
        ))).isFalse();
    }
}
