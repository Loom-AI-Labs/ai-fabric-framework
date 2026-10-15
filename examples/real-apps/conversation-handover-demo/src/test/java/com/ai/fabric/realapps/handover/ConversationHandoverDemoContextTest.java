package com.ai.fabric.realapps.handover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.access.policy.EntityAccessPolicy;
import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:handover-context-test;DB_CLOSE_DELAY=-1",
    "app.demo.operator-access-code=test-operator-code",
    "ai.providers.openai.enabled=true",
    "ai.providers.openai.api-key=startup-test-key"
})
class ConversationHandoverDemoContextTest {

    @Autowired RAGOrchestrator orchestrator;
    @Autowired AIActionRegistry actionRegistry;
    @Autowired EntityAccessPolicy accessPolicy;
    @Autowired ConversationHandoverDemoController controller;

    @Test
    void publicOperatorAccessIsOptInOutsideTheDemoContainer() {
        assertThat(controller.health())
            .containsEntry("publicOperatorEnabled", false)
            .containsEntry("demoOperatorAccessCode", "");
        assertThatThrownBy(() -> controller.operatorSession(
            new MockHttpSession(),
            new ConversationHandoverDemoController.OperatorLogin("demo-handover")
        )).isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void realOrchestrationGraphStartsWithTheHandoverActionRegistered() {
        assertThat(orchestrator).isNotNull();
        assertThat(actionRegistry.getAllMetadata())
                .extracting(metadata -> metadata.getName())
                .contains("request_conversation_handover");
    }

    @Test
    void demoPolicyAllowsOnlySessionOwnedOrchestrationEntry() {
        AIAccessSubjectContext subject = AIAccessSubjectContext.builder()
            .subjectId("demo-owner")
            .sessionId("demo-owner")
            .subjectType("ANONYMOUS_SESSION")
            .build();

        assertThat(accessPolicy.canAccess(subject, Map.of(
            "resourceId", "rag:intent",
            "operationType", "READ"
        ))).isTrue();
        assertThat(accessPolicy.canAccess(subject, Map.of(
            "resourceId", "unrelated-resource",
            "operationType", "READ"
        ))).isFalse();
        assertThat(accessPolicy.canAccess(subject, Map.of(
            "resourceId", "rag:intent",
            "operationType", "WRITE"
        ))).isFalse();
    }
}
