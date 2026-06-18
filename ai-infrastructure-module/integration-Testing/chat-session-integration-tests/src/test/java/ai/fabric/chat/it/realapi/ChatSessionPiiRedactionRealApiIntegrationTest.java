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
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "ai.pii-detection.enabled=true",
        "ai.pii-detection.mode=REDACT",
        "ai.pii-detection.detection-direction=INPUT"
    }
)
@ActiveProfiles("realapi")
class ChatSessionPiiRedactionRealApiIntegrationTest {

    @Autowired
    private RAGOrchestrator orchestrator;

    @Autowired
    private ChatSessionService chatSessionService;

    @Test
    void shouldRedactEmailInRecordedUserQuery() {
        String ownerId = "pii-user-" + UUID.randomUUID();
        String conversationId = "chat-" + UUID.randomUUID();

        OrchestrationContext ctx = OrchestrationContext.builder()
            .userId(ownerId)
            .conversationId(conversationId)
            .build();

        String query = "My email is test@example.com. Please ignore it.";
        OrchestrationResult result = orchestrator.orchestrate(query, ctx);

        assertThat(result).isNotNull();
        assertThat(result.getType()).isNotEqualTo(OrchestrationResultType.ERROR);
        assertThat(result.getMessage()).isNotBlank();

        ChatSession session = chatSessionService.getSession(conversationId, ownerId);
        assertThat(session.getTurns()).isNotEmpty();
        assertThat(session.getTurns().getFirst().getUserQuery())
            .doesNotContain("test@example.com")
            .contains("***@***.***");
    }
}
