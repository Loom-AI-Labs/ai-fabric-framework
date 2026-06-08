package com.ai.infrastructure.chat.service;

import com.ai.infrastructure.chat.config.ChatSessionProperties;
import com.ai.infrastructure.chat.domain.ChatSession;
import com.ai.infrastructure.chat.domain.ChatTurn;
import com.ai.infrastructure.chat.domain.SessionStatus;
import com.ai.infrastructure.chat.exception.ChatSessionAccessDeniedException;
import com.ai.infrastructure.chat.exception.ChatSessionNotFoundException;
import com.ai.infrastructure.chat.spi.ChatSessionAccessControlPolicy;
import com.ai.infrastructure.chat.spi.ChatSessionStorageProvider;
import com.ai.infrastructure.chat.strategy.MemoryStrategy;
import com.ai.infrastructure.dto.AIChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
public class ChatSessionServiceImpl implements ChatSessionService {

    private final ChatSessionStorageProvider storageProvider;
    private final ChatSessionAccessControlPolicy accessPolicy;
    private final MemoryStrategy memoryStrategy;
    private final ChatSessionProperties properties;

    @Override
    @Transactional
    public List<AIChatMessage> getConversationMessages(String conversationId, String ownerId) {
        if (!StringUtils.hasText(conversationId)) {
            return List.of();
        }
        if (!StringUtils.hasText(ownerId)) {
            throw new IllegalArgumentException("ownerId cannot be blank when loading conversation messages");
        }

        if (!accessPolicy.canAccessConversation(ownerId, conversationId)) {
            throw new ChatSessionAccessDeniedException("Access denied to conversation: " + conversationId);
        }

        ChatSession session = storageProvider.findById(conversationId)
            .orElseGet(() -> autoCreateSession(conversationId, ownerId));

        if (!session.isOwnedBy(ownerId)) {
            throw new ChatSessionAccessDeniedException("Conversation is owned by a different user");
        }

        List<ChatTurn> history = session.getTurns() != null ? session.getTurns() : List.of();
        if (history.isEmpty()) {
            return List.of();
        }

        int windowSize = properties != null ? properties.getWindowSize() : 10;
        List<ChatTurn> pruned = memoryStrategy.prune(history, windowSize);

        List<AIChatMessage> messages = memoryStrategy.toMessages(pruned);
        if (messages.isEmpty()) {
            return List.of();
        }

        int maxChars = properties != null ? properties.getMaxContextChars() : 8_000;
        if (maxChars <= 0) {
            return messages;
        }

        // Bound by dropping oldest whole messages; never substring content.
        int totalChars = messages.stream()
            .map(AIChatMessage::getContent)
            .filter(Objects::nonNull)
            .mapToInt(String::length)
            .sum();

        if (totalChars <= maxChars) {
            return messages;
        }

        List<AIChatMessage> bounded = new ArrayList<>(messages);
        while (totalChars > maxChars && !bounded.isEmpty()) {
            AIChatMessage removed = bounded.remove(0);
            if (removed != null && removed.getContent() != null) {
                totalChars -= removed.getContent().length();
            }
        }

        return bounded.isEmpty() ? List.of() : List.copyOf(bounded);
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

        ChatSession session = storageProvider.findById(conversationId)
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

        ChatTurn.ChatTurnBuilder builder = ChatTurn.builder()
            .session(session)
            .userQuery(userQuery)
            .aiResponse(aiResponse)
            .timestamp(LocalDateTime.now());

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
        session.setLastInteractionAt(LocalDateTime.now());

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

        ChatSession session = storageProvider.findById(conversationId)
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

        ChatSession session = storageProvider.findById(conversationId)
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
        return storageProvider.findByOwnerId(ownerId);
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
        storageProvider.findById(conversationId).ifPresent(session -> {
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

        ChatSession created = ChatSession.builder()
            .id(conversationId)
            .ownerId(ownerId)
            .status(SessionStatus.ACTIVE)
            .createdAt(LocalDateTime.now())
            .lastInteractionAt(LocalDateTime.now())
            .build();

        return Objects.requireNonNull(storageProvider.save(created), "storageProvider.save returned null session");
    }
}
