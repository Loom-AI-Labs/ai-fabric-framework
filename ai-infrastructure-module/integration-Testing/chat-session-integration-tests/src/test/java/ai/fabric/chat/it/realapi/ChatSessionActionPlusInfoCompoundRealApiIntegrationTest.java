package ai.fabric.chat.it.realapi;

import ai.fabric.chat.domain.ChatSession;
import ai.fabric.chat.it.ChatSessionIntegrationTestApplication;
import ai.fabric.chat.it.actions.SafeEchoActionHandler;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = ChatSessionIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("realapi")
class ChatSessionActionPlusInfoCompoundRealApiIntegrationTest {

    @Autowired
    private RAGOrchestrator orchestrator;

    @Autowired
    private ChatSessionService chatSessionService;

    @Test
    void shouldHandleActionThenExplanationWithoutError() {
        String ownerId = "compound-action-user-" + UUID.randomUUID();
        String conversationId = "chat-" + UUID.randomUUID();

        OrchestrationContext ctx = OrchestrationContext.builder()
            .userId(ownerId)
            .conversationId(conversationId)
            .build();

        // Step 1: Execute the action explicitly (high reliability across providers).
        OrchestrationResult action = orchestrator.orchestrate(
            "Use the action '" + SafeEchoActionHandler.ACTION_NAME + "' with params: {\"message\":\"hello\"}.",
            ctx
        );

        assertThat(action).isNotNull();
        assertThat(action.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(action.isSuccess()).isTrue();
        assertThat(action.getMessage()).isNotBlank();

        // Step 2: Follow-up explanation based on conversation history.
        // Some providers may still request clarification for retrieval; RealAPI assertion stays structural/non-flaky.
        OrchestrationResult followUp = orchestrator.orchestrate(
            "Briefly explain what you just executed, based only on this conversation. Do not search any knowledge base.",
            ctx
        );

        assertThat(followUp).isNotNull();
        assertThat(followUp.getType()).isNotEqualTo(OrchestrationResultType.ERROR);
        assertThat(followUp.getMessage()).isNotBlank();

        ChatSession session = chatSessionService.getSession(conversationId, ownerId);
        assertThat(session.getTurns()).hasSizeGreaterThanOrEqualTo(2);
    }
}
