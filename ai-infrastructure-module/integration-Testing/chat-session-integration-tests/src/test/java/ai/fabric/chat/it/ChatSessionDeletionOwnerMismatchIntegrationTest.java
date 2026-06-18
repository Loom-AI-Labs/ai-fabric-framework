package ai.fabric.chat.it;

import ai.fabric.chat.exception.ChatSessionAccessDeniedException;
import ai.fabric.chat.service.ChatSessionService;
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
@ActiveProfiles("test")
class ChatSessionDeletionOwnerMismatchIntegrationTest {

    @Autowired
    private ChatSessionService chatSessionService;

    @Test
    void shouldDenyDeletionWhenOwnerDoesNotMatch() {
        String ownerA = "delete-owner-a-" + UUID.randomUUID();
        String ownerB = "delete-owner-b-" + UUID.randomUUID();
        String conversationId = "conv-" + UUID.randomUUID();

        chatSessionService.recordTurn(conversationId, ownerA, "Hello", "Hi", Map.of());

        assertThatThrownBy(() -> chatSessionService.deleteConversation(conversationId, ownerB))
            .isInstanceOf(ChatSessionAccessDeniedException.class);
    }
}
