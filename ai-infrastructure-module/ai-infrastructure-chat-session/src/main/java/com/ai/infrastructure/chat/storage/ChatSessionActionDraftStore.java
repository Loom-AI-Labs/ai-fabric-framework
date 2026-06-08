package com.ai.infrastructure.chat.storage;

import com.ai.infrastructure.chat.domain.ChatSession;
import com.ai.infrastructure.chat.spi.ChatSessionStorageProvider;
import com.ai.infrastructure.chat.util.ActionDraftMetadata;
import com.ai.infrastructure.intent.actiondraft.ActionDraft;
import com.ai.infrastructure.intent.actiondraft.ActionDraftStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.util.StringUtils;

/**
 * Persists an action draft inside {@link ChatSession#sessionMetadata}.
 */
public class ChatSessionActionDraftStore implements ActionDraftStore {

    private final ChatSessionStorageProvider storageProvider;

    public ChatSessionActionDraftStore(ChatSessionStorageProvider storageProvider) {
        this.storageProvider = storageProvider;
    }

    @Override
    public Optional<ActionDraft> peekDraft(String conversationId, String ownerId) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(ownerId)) {
            return Optional.empty();
        }
        return storageProvider.findById(conversationId)
            .filter(session -> session != null && session.isOwnedBy(ownerId))
            .map(ChatSession::getSessionMetadata)
            .map(ActionDraftMetadata::peek)
            .filter(draft -> draft != null);
    }

    @Override
    public void saveDraft(String conversationId, String ownerId, ActionDraft draft) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(ownerId) || draft == null) {
            return;
        }
        storageProvider.findById(conversationId)
            .filter(session -> session != null && session.isOwnedBy(ownerId))
            .ifPresent(session -> {
                Map<String, Object> metadata = session.getSessionMetadata();
                Map<String, Object> mutable = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
                ActionDraftMetadata.put(mutable, draft);
                session.setSessionMetadata(mutable);
                storageProvider.save(session);
            });
    }

    @Override
    public Optional<ActionDraft> popDraft(String conversationId, String ownerId) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(ownerId)) {
            return Optional.empty();
        }
        return storageProvider.findById(conversationId)
            .filter(session -> session != null && session.isOwnedBy(ownerId))
            .map(session -> {
                Map<String, Object> metadata = session.getSessionMetadata();
                if (metadata == null || metadata.isEmpty()) {
                    return null;
                }
                Map<String, Object> mutable = new HashMap<>(metadata);
                ActionDraft existing = ActionDraftMetadata.peek(mutable);
                ActionDraftMetadata.clear(mutable);
                session.setSessionMetadata(mutable);
                storageProvider.save(session);
                return existing;
            })
            .filter(d -> d != null);
    }

    @Override
    public void clearDrafts(String conversationId, String ownerId) {
        popDraft(conversationId, ownerId);
    }
}

