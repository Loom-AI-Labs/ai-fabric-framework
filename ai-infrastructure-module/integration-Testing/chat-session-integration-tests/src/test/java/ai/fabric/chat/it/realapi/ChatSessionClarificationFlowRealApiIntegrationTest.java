package ai.fabric.chat.it.realapi;

import ai.fabric.chat.domain.ChatSession;
import ai.fabric.chat.it.ChatSessionIntegrationTestApplication;
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
class ChatSessionClarificationFlowRealApiIntegrationTest {

    @Autowired
    private RAGOrchestrator orchestrator;

    @Autowired
    private ChatSessionService chatSessionService;

    @Test
    void shouldRecordClarificationTurnAndAllowFollowUp() {
        String ownerId = "clarify-user-" + UUID.randomUUID();
        String conversationId = "chat-" + UUID.randomUUID();

        OrchestrationContext ctx = OrchestrationContext.builder()
            .userId(ownerId)
            .conversationId(conversationId)
            .build();

        OrchestrationResult clarification = orchestrator.orchestrate(
            "Search my knowledge base for the return policy. If you need a domain, ask me.",
            ctx
        );

        assertThat(clarification).isNotNull();
        // Real providers may return generation-only INFORMATION_PROVIDED instead of asking for a domain.
        // We still require the flow to be stable (no ERROR) and for chat history to be recorded.
        assertThat(clarification.getType()).isNotEqualTo(OrchestrationResultType.ERROR);
        assertThat(clarification.getMessage()).isNotBlank();

        ChatSession afterClarification = chatSessionService.getSession(conversationId, ownerId);
        assertThat(afterClarification.getTurns())
            .withFailMessage("Clarification turn should be recorded to preserve chat continuity.")
            .isNotEmpty();

        OrchestrationResult followUp = orchestrator.orchestrate(
            "Use domain ragconversation.",
            ctx
        );

        assertThat(followUp).isNotNull();
        assertThat(followUp.getType()).isNotEqualTo(OrchestrationResultType.ERROR);
        assertThat(followUp.getMessage()).isNotBlank();

        ChatSession afterFollowUp = chatSessionService.getSession(conversationId, ownerId);
        assertThat(afterFollowUp.getTurns()).hasSizeGreaterThanOrEqualTo(2);
    }
}
