package ai.fabric.chat.service;

import ai.fabric.chat.config.ChatSessionProperties;
import ai.fabric.chat.domain.ChatSession;
import ai.fabric.chat.domain.ChatTurn;
import ai.fabric.chat.domain.SessionStatus;
import ai.fabric.chat.exception.ChatSessionAccessDeniedException;
import ai.fabric.chat.exception.ChatSessionNotFoundException;
import ai.fabric.chat.spi.ChatSessionAccessControlPolicy;
import ai.fabric.chat.spi.ChatSessionStorageProvider;
import ai.fabric.chat.strategy.MemoryStrategy;
import ai.fabric.dto.AIChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Slf4j
public class ChatSessionServiceImpl implements ChatSessionService {

    private final ChatSessionStorageProvider storageProvider;
    private final ChatSessionAccessControlPolicy accessPolicy;
    private final MemoryStrategy memoryStrategy;
    private final ChatSessionProperties properties;
    private final Clock clock;

    public ChatSessionServiceImpl(ChatSessionStorageProvider storageProvider,
                                  ChatSessionAccessControlPolicy accessPolicy,
                                  MemoryStrategy memoryStrategy,
                                  ChatSessionProperties properties) {
        this(storageProvider, accessPolicy, memoryStrategy, properties, Clock.systemDefaultZone());
    }

    ChatSessionServiceImpl(ChatSessionStorageProvider storageProvider,
                           ChatSessionAccessControlPolicy accessPolicy,
                           MemoryStrategy memoryStrategy,
                           ChatSessionProperties properties,
                           Clock clock) {
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.memoryStrategy = Objects.requireNonNull(memoryStrategy, "memoryStrategy");
        this.properties = properties;
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
    }

    @Override
    @Transactional
    public List<AIChatMessage> getConversationMessages(String conversationId, String ownerId) {
        return getConversationSnapshot(conversationId, ownerId).messages();
    }

    @Override
    @Transactional
    public ConversationHistorySnapshot getConversationSnapshot(
        String conversationId,
        String ownerId
    ) {
        if (!StringUtils.hasText(conversationId)) {
            return new ConversationHistorySnapshot(0L, List.of());
        }
        if (!StringUtils.hasText(ownerId)) {
            throw new IllegalArgumentException("ownerId cannot be blank when loading conversation messages");
        }

        if (!accessPolicy.canAccessConversation(ownerId, conversationId)) {
            throw new ChatSessionAccessDeniedException("Access denied to conversation: " + conversationId);
        }

        ChatSession session = findSession(conversationId)
            .orElseGet(() -> autoCreateSession(conversationId, ownerId));

        if (!session.isOwnedBy(ownerId)) {
            throw new ChatSessionAccessDeniedException("Conversation is owned by a different user");
        }

        List<ChatTurn> history = session.getTurns() != null ? session.getTurns() : List.of();
        long sourceTurnCount = history.size();
        if (history.isEmpty()) {
            return new ConversationHistorySnapshot(sourceTurnCount, List.of());
        }

        int windowSize = properties != null ? properties.getWindowSize() : 10;
        List<ChatTurn> pruned = memoryStrategy.prune(history, windowSize);
        if (pruned == null || pruned.isEmpty()) {
            return new ConversationHistorySnapshot(sourceTurnCount, List.of());
        }

        List<AIChatMessage> messages = memoryStrategy.toMessages(pruned);
        if (messages == null || messages.isEmpty()) {
            return new ConversationHistorySnapshot(sourceTurnCount, List.of());
        }

        int maxChars = properties != null ? properties.getMaxContextChars() : 8_000;
        if (maxChars <= 0) {
            return new ConversationHistorySnapshot(
                sourceTurnCount,
                messages
            );
        }

        // Bound by dropping oldest whole messages; never substring content.
        int totalChars = messages.stream()
            .map(AIChatMessage::getContent)
            .filter(Objects::nonNull)
            .mapToInt(String::length)
            .sum();

        if (totalChars <= maxChars) {
            return new ConversationHistorySnapshot(
                sourceTurnCount,
                messages
            );
        }

        List<AIChatMessage> bounded = new ArrayList<>(messages);
        while (totalChars > maxChars && !bounded.isEmpty()) {
            AIChatMessage removed = bounded.remove(0);
            if (removed != null && removed.getContent() != null) {
                totalChars -= removed.getContent().length();
            }
        }

        return new ConversationHistorySnapshot(
            sourceTurnCount,
            bounded
        );
    }

    @Override
    @Transactional
    public void recordTurn(String conversationId,
                           String ownerId,
                           String userQuery,
                           String aiResponse,
                           Map<String, Object> turnMetadata) {
        if (!StringUtils.hasText(conversationId)) {
            return;
        }
        if (!StringUtils.hasText(ownerId)) {
            throw new IllegalArgumentException("ownerId cannot be blank when recording a conversation turn");
        }
        if (!accessPolicy.canRecordTurn(ownerId, conversationId)) {
            throw new ChatSessionAccessDeniedException("Access denied recording conversation: " + conversationId);
        }

        ChatSession session = findSession(conversationId)
            .orElseGet(() -> autoCreateSession(conversationId, ownerId));

        if (!session.isOwnedBy(ownerId)) {
            throw new ChatSessionAccessDeniedException("Conversation is owned by a different user");
        }

        if (session.getStatus() != SessionStatus.ACTIVE) {
            log.debug("Skipping record turn for non-active session conversationId={}, status={}", conversationId, session.getStatus());
            return;
        }

        if (!StringUtils.hasText(userQuery) || !StringUtils.hasText(aiResponse)) {
            return;
        }

        LocalDateTime now = now();
        ChatTurn.ChatTurnBuilder builder = ChatTurn.builder()
            .session(session)
            .userQuery(userQuery)
            .aiResponse(aiResponse)
            .timestamp(now);

        if (turnMetadata != null && !turnMetadata.isEmpty()) {
            builder.turnMetadata(new LinkedHashMap<>(turnMetadata));
        }

        ChatTurn turn = builder.build();

        List<ChatTurn> turns = session.getTurns();
        if (turns == null) {
            turns = new ArrayList<>();
            session.setTurns(turns);
        }
        turns.add(turn);
        session.setLastInteractionAt(now);

        storageProvider.save(session);
    }

    @Override
    @Transactional(readOnly = true)
    public ChatSession getSession(String conversationId, String ownerId) {
        if (!StringUtils.hasText(conversationId)) {
            throw new IllegalArgumentException("conversationId cannot be blank");
        }
        if (!StringUtils.hasText(ownerId)) {
            throw new IllegalArgumentException("ownerId cannot be blank");
        }

        if (!accessPolicy.canAccessConversation(ownerId, conversationId)) {
            throw new ChatSessionAccessDeniedException("Access denied to conversation: " + conversationId);
        }

        ChatSession session = findSession(conversationId)
            .orElseThrow(() -> new ChatSessionNotFoundException("Conversation not found: " + conversationId));

        if (!session.isOwnedBy(ownerId)) {
            throw new ChatSessionAccessDeniedException("Conversation is owned by a different user");
        }

        return session;
    }

    @Override
    @Transactional
    public void mergeSessionMetadata(String conversationId, String ownerId, Map<String, Object> updates) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(ownerId)) {
            return;
        }
        if (updates == null || updates.isEmpty()) {
            return;
        }

        if (!accessPolicy.canAccessConversation(ownerId, conversationId)) {
            throw new ChatSessionAccessDeniedException("Access denied to conversation: " + conversationId);
        }

        ChatSession session = findSession(conversationId)
            .orElseGet(() -> autoCreateSession(conversationId, ownerId));

        if (!session.isOwnedBy(ownerId)) {
            throw new ChatSessionAccessDeniedException("Conversation is owned by a different user");
        }

        Map<String, Object> merged = new LinkedHashMap<>();
        if (session.getSessionMetadata() != null && !session.getSessionMetadata().isEmpty()) {
            merged.putAll(session.getSessionMetadata());
        }
        merged.putAll(updates);

        session.setSessionMetadata(merged);
        storageProvider.save(session);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatSession> getUserConversations(String ownerId) {
        if (!StringUtils.hasText(ownerId)) {
            return List.of();
        }
        List<ChatSession> conversations = storageProvider.findByOwnerId(ownerId);
        return conversations != null ? conversations : List.of();
    }

    @Override
    @Transactional
    public void deleteConversation(String conversationId, String ownerId) {
        if (!StringUtils.hasText(conversationId)) {
            return;
        }
        if (!StringUtils.hasText(ownerId)) {
            throw new IllegalArgumentException("ownerId cannot be blank");
        }
        // Fail-closed: never allow deletion without verifying ownership when the conversation exists.
        findSession(conversationId).ifPresent(session -> {
            if (!session.isOwnedBy(ownerId)) {
                throw new ChatSessionAccessDeniedException("Conversation is owned by a different user");
            }
        });
        if (!accessPolicy.canDeleteConversation(ownerId, conversationId)) {
            throw new ChatSessionAccessDeniedException("Access denied deleting conversation: " + conversationId);
        }
        storageProvider.deleteById(conversationId);
    }

    private ChatSession autoCreateSession(String conversationId, String ownerId) {
        if (properties != null && !properties.isAutoCreateSessions()) {
            throw new ChatSessionNotFoundException("Conversation not found: " + conversationId);
        }
        if (!accessPolicy.canCreateConversation(ownerId)) {
            throw new ChatSessionAccessDeniedException("Conversation creation not allowed for ownerId: " + ownerId);
        }

        LocalDateTime now = now();
        ChatSession created = ChatSession.builder()
            .id(conversationId)
            .ownerId(ownerId)
            .status(SessionStatus.ACTIVE)
            .createdAt(now)
            .lastInteractionAt(now)
            .build();

        return Objects.requireNonNull(storageProvider.save(created), "storageProvider.save returned null session");
    }

    private Optional<ChatSession> findSession(String conversationId) {
        Optional<ChatSession> session = storageProvider.findById(conversationId);
        return session != null ? session : Optional.empty();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
