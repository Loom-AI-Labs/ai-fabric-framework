package com.ai.infrastructure.intent.actiondraft;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.util.StringUtils;

/**
 * Simple in-memory action draft store (non-persistent).
 */
public class InMemoryActionDraftStore implements ActionDraftStore {

    private final ConcurrentMap<String, ActionDraft> store = new ConcurrentHashMap<>();

    @Override
    public Optional<ActionDraft> peekDraft(String conversationId, String ownerId) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(ownerId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(key(conversationId, ownerId)));
    }

    @Override
    public void saveDraft(String conversationId, String ownerId, ActionDraft draft) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(ownerId) || draft == null) {
            return;
        }
        store.put(key(conversationId, ownerId), draft);
    }

    @Override
    public Optional<ActionDraft> popDraft(String conversationId, String ownerId) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(ownerId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.remove(key(conversationId, ownerId)));
    }

    @Override
    public void clearDrafts(String conversationId, String ownerId) {
        popDraft(conversationId, ownerId);
    }

    private String key(String conversationId, String ownerId) {
        return Objects.requireNonNull(conversationId) + "::" + Objects.requireNonNull(ownerId);
    }
}

