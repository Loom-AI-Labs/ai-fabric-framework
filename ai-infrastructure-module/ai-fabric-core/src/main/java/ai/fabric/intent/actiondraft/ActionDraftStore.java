package ai.fabric.intent.actiondraft;

import ai.fabric.intent.orchestration.conversation.ConversationStateScope;
import java.util.Optional;

/**
 * Storage abstraction for incomplete actions awaiting missing required parameters.
 *
 * <p>Implementations are expected to scope drafts by (conversationId, ownerId).</p>
 */
public interface ActionDraftStore {

    Optional<ActionDraft> peekDraft(String conversationId, String ownerId);

    void saveDraft(String conversationId, String ownerId, ActionDraft draft);

    Optional<ActionDraft> popDraft(String conversationId, String ownerId);

    void clearDrafts(String conversationId, String ownerId);

    default Optional<ActionDraft> peekDraft(ConversationStateScope scope) {
        throw canonicalScopeUnavailable();
    }

    default void saveDraft(ConversationStateScope scope, ActionDraft draft) {
        throw canonicalScopeUnavailable();
    }

    default Optional<ActionDraft> popDraft(ConversationStateScope scope) {
        throw canonicalScopeUnavailable();
    }

    default void clearDrafts(ConversationStateScope scope) {
        throw canonicalScopeUnavailable();
    }

    private static IllegalStateException canonicalScopeUnavailable() {
        return new IllegalStateException(
            "Canonical conversation scope requires a scope-aware action-draft store"
        );
    }
}

