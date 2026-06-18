package ai.fabric.chat.it.realapi;

import ai.fabric.chat.exception.ChatSessionAccessDeniedException;
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
class ChatSessionOwnerMismatchRealApiIntegrationTest {

    @Autowired
    private ChatSessionService chatSessionService;

    @Test
    void shouldDenyAccessWhenOwnerDoesNotMatch() {
        String ownerA = "owner-a-" + UUID.randomUUID();
        String ownerB = "owner-b-" + UUID.randomUUID();
        String conversationId = "chat-" + UUID.randomUUID();

        chatSessionService.recordTurn(conversationId, ownerA, "Hello", "Hi", Map.of());

        assertThatThrownBy(() -> chatSessionService.getSession(conversationId, ownerB))
            .isInstanceOf(ChatSessionAccessDeniedException.class);
    }
}
