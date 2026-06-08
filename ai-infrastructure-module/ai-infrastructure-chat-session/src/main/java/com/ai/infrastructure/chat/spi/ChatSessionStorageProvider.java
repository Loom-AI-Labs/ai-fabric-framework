package com.ai.infrastructure.chat.spi;

import com.ai.infrastructure.chat.domain.ChatSession;

import java.util.List;
import java.util.Optional;

/**
 * SPI for chat session storage.
 *
 * <p>Framework provides a default JPA implementation; applications can override with a custom store (Redis/Mongo/etc.).</p>
 */
public interface ChatSessionStorageProvider {

    ChatSession save(ChatSession session);

    Optional<ChatSession> findById(String conversationId);

    void deleteById(String conversationId);

    List<ChatSession> findByOwnerId(String ownerId);
}

