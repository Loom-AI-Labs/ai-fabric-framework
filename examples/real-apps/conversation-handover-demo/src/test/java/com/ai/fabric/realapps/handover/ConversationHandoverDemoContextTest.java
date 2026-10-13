package com.ai.fabric.realapps.handover;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:handover-context-test;DB_CLOSE_DELAY=-1",
    "app.demo.operator-access-code=test-operator-code",
    "ai.providers.openai.enabled=true",
    "ai.providers.openai.api-key=startup-test-key"
})
class ConversationHandoverDemoContextTest {

    @Autowired RAGOrchestrator orchestrator;
    @Autowired AIActionRegistry actionRegistry;

    @Test
    void realOrchestrationGraphStartsWithTheHandoverActionRegistered() {
        assertThat(orchestrator).isNotNull();
        assertThat(actionRegistry.getAllMetadata())
                .extracting(metadata -> metadata.getName())
                .contains("request_conversation_handover");
    }
}
