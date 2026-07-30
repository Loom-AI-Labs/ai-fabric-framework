package ai.fabric.chat.service;

import ai.fabric.chat.config.ChatSessionProperties;
import ai.fabric.chat.domain.ChatSession;
import ai.fabric.chat.domain.ChatTurn;
import ai.fabric.chat.domain.SessionStatus;
import ai.fabric.chat.exception.ChatSessionAccessDeniedException;
import ai.fabric.chat.spi.ChatSessionAccessControlPolicy;
import ai.fabric.chat.spi.ChatSessionStorageProvider;
import ai.fabric.chat.strategy.MemoryStrategy;
import ai.fabric.chat.strategy.SlidingWindowMemoryStrategy;
import ai.fabric.dto.AIChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatSessionServiceImplTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-18T08:30:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime FIXED_TIME = LocalDateTime.ofInstant(FIXED_CLOCK.instant(), ZoneOffset.UTC);

    @Test
    void getConversationMessagesShouldAutoCreateMissingSessionWithFixedTimestamp() {
        InMemoryStorage storage = new InMemoryStorage();
        ChatSessionServiceImpl service = service(storage, allowAll(), new SlidingWindowMemoryStrategy(), properties());

        List<AIChatMessage> messages = service.getConversationMessages("conv-1", "user-1");

        assertThat(messages).isEmpty();
        ChatSession created = storage.sessions.get("conv-1");
        assertThat(created).isNotNull();
        assertThat(created.getOwnerId()).isEqualTo("user-1");
        assertThat(created.getStatus()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(created.getCreatedAt()).isEqualTo(FIXED_TIME);
        assertThat(created.getLastInteractionAt()).isEqualTo(FIXED_TIME);
    }

    @Test
    void recordTurnShouldAppendTurnAndUseSingleClockInstant() {
        InMemoryStorage storage = new InMemoryStorage();
        ChatSession session = session("conv-1", "user-1");
        storage.save(session);
        ChatSessionServiceImpl service = service(storage, allowAll(), new SlidingWindowMemoryStrategy(), properties());

        service.recordTurn("conv-1", "user-1", "hello", "hi", Map.of("k", "v"));

        assertThat(session.getTurns()).hasSize(1);
        ChatTurn turn = session.getTurns().getFirst();
        assertThat(turn.getSession()).isSameAs(session);
        assertThat(turn.getUserQuery()).isEqualTo("hello");
        assertThat(turn.getAiResponse()).isEqualTo("hi");
        assertThat(turn.getTimestamp()).isEqualTo(FIXED_TIME);
        assertThat(turn.getTurnMetadata()).containsEntry("k", "v");
        assertThat(session.getLastInteractionAt()).isEqualTo(FIXED_TIME);
    }

    @Test
    void getConversationMessagesShouldDropOldestWholeMessagesWhenContextIsTooLarge() {
        InMemoryStorage storage = new InMemoryStorage();
        ChatSession session = session("conv-1", "user-1");
        session.getTurns().add(ChatTurn.builder().userQuery("aaaaa").aiResponse("bbbbb").build());
        session.getTurns().add(ChatTurn.builder().userQuery("c").aiResponse("d").build());
        storage.save(session);

        ChatSessionProperties properties = properties();
        properties.setMaxContextChars(6);
        ChatSessionServiceImpl service = service(storage, allowAll(), new SlidingWindowMemoryStrategy(), properties);

        List<AIChatMessage> messages = service.getConversationMessages("conv-1", "user-1");

        assertThat(messages).extracting(AIChatMessage::getContent).containsExactly("c", "d");
    }

    @Test
    void getConversationSnapshotCapturesBoundedMessagesAndFullTurnCount() {
        InMemoryStorage storage = new InMemoryStorage();
        ChatSession session = session("conv-1", "user-1");
        session.getTurns().add(
            ChatTurn.builder()
                .userQuery("old question")
                .aiResponse("old answer")
                .build()
        );
        session.getTurns().add(
            ChatTurn.builder()
                .userQuery("new question")
                .aiResponse("new answer")
                .build()
        );
        storage.save(session);
        ChatSessionProperties properties = properties();
        properties.setWindowSize(1);
        ChatSessionServiceImpl service = service(
            storage,
            allowAll(),
            new SlidingWindowMemoryStrategy(),
            properties
        );

        ConversationHistorySnapshot snapshot =
            service.getConversationSnapshot("conv-1", "user-1");

        assertThat(snapshot.sourceTurnCount()).isEqualTo(2);
        assertThat(snapshot.messages())
            .extracting(AIChatMessage::getContent)
            .containsExactly("new question", "new answer");
    }

    @Test
    void getConversationMessagesShouldReturnEmptyWhenCustomStrategyReturnsNull() {
        InMemoryStorage storage = new InMemoryStorage();
        ChatSession session = session("conv-1", "user-1");
        session.getTurns().add(ChatTurn.builder().userQuery("hello").aiResponse("hi").build());
        storage.save(session);

        ChatSessionServiceImpl service = service(storage, allowAll(), new NullMemoryStrategy(), properties());

        assertThat(service.getConversationMessages("conv-1", "user-1")).isEmpty();
    }

    @Test
    void getUserConversationsShouldReturnEmptyWhenStorageReturnsNull() {
        InMemoryStorage storage = new InMemoryStorage() {
            @Override
            public List<ChatSession> findByOwnerId(String ownerId) {
                return null;
            }
        };
        ChatSessionServiceImpl service = service(storage, allowAll(), new SlidingWindowMemoryStrategy(), properties());

        assertThat(service.getUserConversations("user-1")).isEmpty();
    }

    @Test
    void getSessionShouldDenyDifferentOwnerEvenWhenAccessPolicyAllowsConversation() {
        InMemoryStorage storage = new InMemoryStorage();
        storage.save(session("conv-1", "other-user"));
        ChatSessionServiceImpl service = service(storage, allowAll(), new SlidingWindowMemoryStrategy(), properties());

        assertThatThrownBy(() -> service.getSession("conv-1", "user-1"))
            .isInstanceOf(ChatSessionAccessDeniedException.class)
            .hasMessageContaining("different user");
    }

    private ChatSessionServiceImpl service(InMemoryStorage storage,
                                           ChatSessionAccessControlPolicy policy,
                                           MemoryStrategy memoryStrategy,
                                           ChatSessionProperties properties) {
        return new ChatSessionServiceImpl(storage, policy, memoryStrategy, properties, FIXED_CLOCK);
    }

    private ChatSessionProperties properties() {
        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);
        properties.setWindowSize(10);
        properties.setMaxContextChars(8_000);
        properties.setAutoCreateSessions(true);
        return properties;
    }

    private ChatSession session(String id, String ownerId) {
        return ChatSession.builder()
            .id(id)
            .ownerId(ownerId)
            .status(SessionStatus.ACTIVE)
            .createdAt(FIXED_TIME.minusMinutes(1))
            .lastInteractionAt(FIXED_TIME.minusMinutes(1))
            .turns(new ArrayList<>())
            .sessionMetadata(new LinkedHashMap<>())
            .build();
    }

    private ChatSessionAccessControlPolicy allowAll() {
        return new ChatSessionAccessControlPolicy() {
            @Override
            public boolean canCreateConversation(String ownerId) {
                return true;
            }

            @Override
            public boolean canAccessConversation(String requestingUserId, String conversationId) {
                return true;
            }

            @Override
            public boolean canRecordTurn(String requestingUserId, String conversationId) {
                return true;
            }

            @Override
            public boolean canDeleteConversation(String requestingUserId, String conversationId) {
                return true;
            }
        };
    }

    private static class InMemoryStorage implements ChatSessionStorageProvider {
        final Map<String, ChatSession> sessions = new LinkedHashMap<>();

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

    private static class NullMemoryStrategy implements MemoryStrategy {
        @Override
        public List<ChatTurn> prune(List<ChatTurn> history, int limit) {
            return null;
        }

        @Override
        public List<AIChatMessage> toMessages(List<ChatTurn> prunedHistory) {
            return null;
        }

        @Override
        public String getStrategyName() {
            return "NULL";
        }
    }
}
