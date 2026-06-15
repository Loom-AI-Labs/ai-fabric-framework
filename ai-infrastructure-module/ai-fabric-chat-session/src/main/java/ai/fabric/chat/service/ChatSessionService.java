package ai.fabric.chat.service;

import ai.fabric.chat.domain.ChatSession;
import ai.fabric.dto.AIChatMessage;

import java.util.List;
import java.util.Map;

public interface ChatSessionService {

    /**
     * Load conversation history as structured chat messages suitable for provider-native multi-message prompting.
     */
    List<AIChatMessage> getConversationMessages(String conversationId, String ownerId);

    void recordTurn(String conversationId, String ownerId, String userQuery, String aiResponse, Map<String, Object> turnMetadata);

    ChatSession getSession(String conversationId, String ownerId);

    void mergeSessionMetadata(String conversationId, String ownerId, Map<String, Object> updates);

    List<ChatSession> getUserConversations(String ownerId);

    void deleteConversation(String conversationId, String ownerId);
}
