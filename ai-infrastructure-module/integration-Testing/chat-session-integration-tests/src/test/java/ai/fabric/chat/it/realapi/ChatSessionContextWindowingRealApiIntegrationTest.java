package ai.fabric.chat.it.realapi;

import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.chat.it.ChatSessionIntegrationTestApplication;
import ai.fabric.dto.AIChatRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = ChatSessionIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "ai.chat.window-size=2",
        "ai.chat.max-context-chars=8000"
    }
)
@ActiveProfiles("realapi")
class ChatSessionContextWindowingRealApiIntegrationTest {

    @Autowired
    private ChatSessionService chatSessionService;

    @Test
    void shouldReturnOnlyLastNConversationTurnsInContext() {
        String ownerId = "window-user-" + UUID.randomUUID();
        String conversationId = "chat-" + UUID.randomUUID();

        chatSessionService.recordTurn(conversationId, ownerId, "Q1", "A1", Map.of());
        chatSessionService.recordTurn(conversationId, ownerId, "Q2", "A2", Map.of());
        chatSessionService.recordTurn(conversationId, ownerId, "Q3", "A3", Map.of());

        var messages = chatSessionService.getConversationMessages(conversationId, ownerId);

        assertThat(messages.stream().anyMatch(m -> AIChatRole.USER.equals(m.getRole()) && "Q2".equals(m.getContent()))).isTrue();
        assertThat(messages.stream().anyMatch(m -> AIChatRole.USER.equals(m.getRole()) && "Q3".equals(m.getContent()))).isTrue();
        assertThat(messages.stream().anyMatch(m -> AIChatRole.USER.equals(m.getRole()) && "Q1".equals(m.getContent()))).isFalse();
    }
}
