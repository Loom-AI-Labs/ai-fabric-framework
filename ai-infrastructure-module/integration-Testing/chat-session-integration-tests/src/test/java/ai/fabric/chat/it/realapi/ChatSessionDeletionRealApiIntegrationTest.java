package ai.fabric.chat.it.realapi;

import ai.fabric.chat.exception.ChatSessionNotFoundException;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.chat.it.ChatSessionIntegrationTestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
    classes = ChatSessionIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("realapi")
class ChatSessionDeletionRealApiIntegrationTest {

    @Autowired
    private ChatSessionService chatSessionService;

    @Test
    void shouldDeleteConversationAndSubsequentReadsFail() {
        String ownerId = "delete-user-" + UUID.randomUUID();
        String conversationId = "chat-" + UUID.randomUUID();

        chatSessionService.recordTurn(conversationId, ownerId, "Hello", "Hi", Map.of());
        chatSessionService.deleteConversation(conversationId, ownerId);

        assertThatThrownBy(() -> chatSessionService.getSession(conversationId, ownerId))
            .isInstanceOf(ChatSessionNotFoundException.class);
    }
}
