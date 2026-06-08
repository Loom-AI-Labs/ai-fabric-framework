package com.ai.infrastructure.chat.storage;

import com.ai.infrastructure.chat.domain.ChatSession;
import com.ai.infrastructure.chat.repository.ChatSessionRepository;
import com.ai.infrastructure.chat.spi.ChatSessionStorageProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class DefaultDatabaseChatSessionStorage implements ChatSessionStorageProvider {

    private final ChatSessionRepository repository;

    @Override
    public ChatSession save(ChatSession session) {
        ChatSession saved = repository.save(session);
        log.debug("Chat session saved: conversationId={}, ownerId={}", saved.getId(), saved.getOwnerId());
        return saved;
    }

    @Override
    public Optional<ChatSession> findById(String conversationId) {
        return repository.findWithTurnsById(conversationId);
    }

    @Override
    public void deleteById(String conversationId) {
        repository.deleteById(conversationId);
        log.debug("Chat session deleted: conversationId={}", conversationId);
    }

    @Override
    public List<ChatSession> findByOwnerId(String ownerId) {
        return repository.findWithTurnsByOwnerId(ownerId);
    }
}
