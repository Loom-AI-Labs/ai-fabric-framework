package ai.fabric.chat.storage;

import ai.fabric.chat.domain.ChatSession;
import ai.fabric.chat.domain.SessionStatus;
import ai.fabric.chat.spi.ChatSessionStorageProvider;
import ai.fabric.chat.util.ConfirmationStack;
import ai.fabric.intent.action.PendingAction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ChatSessionPendingActionStoreTest {

    @Test
    void shouldPushPeekPopAndCleanEmptyConfirmationStack() {
        InMemoryStorage storage = new InMemoryStorage();
        ChatSession session = session("conv-1", "user-1", Map.of("existing", "kept"));
        storage.save(session);
        ChatSessionPendingActionStore store = new ChatSessionPendingActionStore(storage);

        PendingAction first = pending("first_action");
        PendingAction second = pending("second_action");

        store.pushPendingAction("conv-1", "user-1", first);
        store.pushPendingAction("conv-1", "user-1", second);

        assertThat(store.peekPendingAction("conv-1", "user-1")).contains(second);
        assertThat(store.getPendingActionStack("conv-1", "user-1"))
            .extracting(PendingAction::action)
            .containsExactly("second_action", "first_action");

        assertThat(store.popPendingAction("conv-1", "user-1")).contains(second);
        assertThat(store.peekPendingAction("conv-1", "user-1")).contains(first);

        assertThat(store.popPendingAction("conv-1", "user-1")).contains(first);
        assertThat(store.peekPendingAction("conv-1", "user-1")).isEmpty();
        assertThat(session.getSessionMetadata())
            .containsEntry("existing", "kept")
            .doesNotContainKey(ConfirmationStack.METADATA_KEY_STACK);
    }

    @Test
    void shouldReplaceStackInTopFirstOrder() {
        InMemoryStorage storage = new InMemoryStorage();
        storage.save(session("conv-1", "user-1", Map.of()));
        ChatSessionPendingActionStore store = new ChatSessionPendingActionStore(storage);

        store.replacePendingActionStack("conv-1", "user-1", List.of(
            pending("top_action"),
            pending("bottom_action")
        ));

        assertThat(store.getPendingActionStack("conv-1", "user-1"))
            .extracting(PendingAction::action)
            .containsExactly("top_action", "bottom_action");
        assertThat(store.popPendingAction("conv-1", "user-1").orElseThrow().action()).isEqualTo("top_action");
    }

    @Test
    void shouldIgnoreSessionsOwnedByAnotherUser() {
        InMemoryStorage storage = new InMemoryStorage();
        ChatSession session = session("conv-1", "owner-1", Map.of());
        storage.save(session);
        ChatSessionPendingActionStore store = new ChatSessionPendingActionStore(storage);

        store.pushPendingAction("conv-1", "other-user", pending("forbidden_action"));

        assertThat(store.peekPendingAction("conv-1", "owner-1")).isEmpty();
        assertThat(session.getSessionMetadata()).doesNotContainKey(ConfirmationStack.METADATA_KEY_STACK);
    }

    private PendingAction pending(String action) {
        return new PendingAction(action, Map.of("id", action + "-id"), null, Instant.parse("2026-06-18T08:30:00Z"));
    }

    private ChatSession session(String id, String ownerId, Map<String, Object> metadata) {
        return ChatSession.builder()
            .id(id)
            .ownerId(ownerId)
            .status(SessionStatus.ACTIVE)
            .createdAt(LocalDateTime.parse("2026-06-18T08:29:00"))
            .lastInteractionAt(LocalDateTime.parse("2026-06-18T08:29:00"))
            .turns(new ArrayList<>())
            .sessionMetadata(new LinkedHashMap<>(metadata))
            .build();
    }

    private static class InMemoryStorage implements ChatSessionStorageProvider {
        private final Map<String, ChatSession> sessions = new LinkedHashMap<>();

        @Override
        public ChatSession save(ChatSession session) {
            sessions.put(session.getId(), session);
            return session;
        }

        @Override
        public Optional<ChatSession> findById(String conversationId) {
            return Optional.ofNullable(sessions.get(conversationId));
        }

        @Override
        public void deleteById(String conversationId) {
            sessions.remove(conversationId);
        }

        @Override
        public List<ChatSession> findByOwnerId(String ownerId) {
            return sessions.values().stream()
                .filter(session -> session.isOwnedBy(ownerId))
                .toList();
        }
    }
}
