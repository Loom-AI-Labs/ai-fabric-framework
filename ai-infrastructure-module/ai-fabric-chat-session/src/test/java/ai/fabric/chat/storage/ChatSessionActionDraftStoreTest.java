package ai.fabric.chat.storage;

import ai.fabric.chat.domain.ChatSession;
import ai.fabric.chat.domain.SessionStatus;
import ai.fabric.chat.spi.ChatSessionStorageProvider;
import ai.fabric.chat.util.ActionDraftMetadata;
import ai.fabric.intent.actiondraft.ActionDraft;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ChatSessionActionDraftStoreTest {

    @Test
    void shouldSavePeekAndPopDraftWithoutDroppingOtherMetadata() {
        InMemoryStorage storage = new InMemoryStorage();
        ChatSession session = session("conv-1", "user-1", Map.of("existing", "kept"));
        storage.save(session);
        ChatSessionActionDraftStore store = new ChatSessionActionDraftStore(storage);
        ActionDraft draft = draft("update_order");

        store.saveDraft("conv-1", "user-1", draft);

        assertThat(store.peekDraft("conv-1", "user-1")).contains(draft);
        assertThat(session.getSessionMetadata()).containsEntry("existing", "kept");

        assertThat(store.popDraft("conv-1", "user-1")).contains(draft);
        assertThat(store.peekDraft("conv-1", "user-1")).isEmpty();
        assertThat(session.getSessionMetadata())
            .containsEntry("existing", "kept")
            .doesNotContainKey(ActionDraftMetadata.METADATA_KEY_DRAFT);
    }

    @Test
    void shouldClearMalformedDraftMetadataOnPop() {
        InMemoryStorage storage = new InMemoryStorage();
        ChatSession session = session("conv-1", "user-1", Map.of(
            "existing", "kept",
            ActionDraftMetadata.METADATA_KEY_DRAFT, "not-a-map"
        ));
        storage.save(session);
        ChatSessionActionDraftStore store = new ChatSessionActionDraftStore(storage);

        assertThat(store.popDraft("conv-1", "user-1")).isEmpty();

        assertThat(session.getSessionMetadata())
            .containsEntry("existing", "kept")
            .doesNotContainKey(ActionDraftMetadata.METADATA_KEY_DRAFT);
    }

    @Test
    void shouldIgnoreSessionsOwnedByAnotherUser() {
        InMemoryStorage storage = new InMemoryStorage();
        ChatSession session = session("conv-1", "owner-1", Map.of());
        storage.save(session);
        ChatSessionActionDraftStore store = new ChatSessionActionDraftStore(storage);

        store.saveDraft("conv-1", "other-user", draft("forbidden_action"));

        assertThat(store.peekDraft("conv-1", "owner-1")).isEmpty();
        assertThat(session.getSessionMetadata()).doesNotContainKey(ActionDraftMetadata.METADATA_KEY_DRAFT);
    }

    private ActionDraft draft(String action) {
        Instant timestamp = Instant.parse("2026-06-18T08:30:00Z");
        return new ActionDraft(action, Map.of("id", action + "-id"), "missing required fields", timestamp, timestamp);
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
