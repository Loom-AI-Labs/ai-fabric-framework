package ai.fabric.intent.actiondraft;

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
}

