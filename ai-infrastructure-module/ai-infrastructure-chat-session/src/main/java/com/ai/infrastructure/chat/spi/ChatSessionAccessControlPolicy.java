package com.ai.infrastructure.chat.spi;

/**
 * Access control SPI for chat sessions.
 *
 * <p>This policy is required when {@code ai.chat.enabled=true} to avoid silent security gaps.</p>
 */
public interface ChatSessionAccessControlPolicy {

    boolean canCreateConversation(String ownerId);

    boolean canAccessConversation(String requestingUserId, String conversationId);

    boolean canRecordTurn(String requestingUserId, String conversationId);

    boolean canDeleteConversation(String requestingUserId, String conversationId);
}

