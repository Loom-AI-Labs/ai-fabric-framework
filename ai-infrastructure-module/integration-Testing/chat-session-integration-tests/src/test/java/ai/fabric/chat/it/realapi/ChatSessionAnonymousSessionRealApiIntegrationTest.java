package ai.fabric.chat.it.realapi;

import ai.fabric.chat.domain.ChatSession;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.chat.it.ChatSessionIntegrationTestApplication;
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
class ChatSessionAnonymousSessionRealApiIntegrationTest {

    @Autowired
    private RAGOrchestrator orchestrator;

    @Autowired
    private ChatSessionService chatSessionService;

    @Test
    void shouldRecordTurnsForAnonymousSessionIdOwner() {
        String sessionId = "anon-session-" + UUID.randomUUID();
        String conversationId = "chat-" + UUID.randomUUID();

        OrchestrationContext ctx = OrchestrationContext.builder()
            .sessionId(sessionId)
            .conversationId(conversationId)
            .build();

        OrchestrationResult result = orchestrator.orchestrate("Hello. Reply with a single short sentence.", ctx);
        assertThat(result).isNotNull();
        assertThat(result.getType()).isNotEqualTo(OrchestrationResultType.ERROR);
        assertThat(result.getMessage()).isNotBlank();

        ChatSession session = chatSessionService.getSession(conversationId, sessionId);
        assertThat(session.getOwnerId()).isEqualTo(sessionId);
        assertThat(session.getTurns()).isNotEmpty();
    }
}
