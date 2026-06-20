package ai.fabric.chat.storage;

import ai.fabric.chat.config.ChatSessionProperties;
import ai.fabric.chat.domain.ChatSession;
import ai.fabric.chat.spi.ChatSessionStorageProvider;
import ai.fabric.chat.util.ConfirmationStack;
import ai.fabric.intent.action.PendingAction;
import ai.fabric.intent.action.PendingActionStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.util.StringUtils;

/**
 * Persists pending (confirmation-required) actions inside {@link ChatSession#sessionMetadata}.
 */
public class ChatSessionPendingActionStore implements PendingActionStore {

    static final int DEFAULT_MAX_STACK_DEPTH = 8;

    private final ChatSessionStorageProvider storageProvider;
    private final int maxStackDepth;

    public ChatSessionPendingActionStore(ChatSessionStorageProvider storageProvider) {
        this(storageProvider, DEFAULT_MAX_STACK_DEPTH);
    }

    public ChatSessionPendingActionStore(ChatSessionStorageProvider storageProvider,
                                         ChatSessionProperties properties) {
        this(
            storageProvider,
            properties != null ? properties.getMaxPendingActionStackDepth() : DEFAULT_MAX_STACK_DEPTH
        );
    }

    ChatSessionPendingActionStore(ChatSessionStorageProvider storageProvider, int maxStackDepth) {
        this.storageProvider = storageProvider;
        this.maxStackDepth = Math.max(1, maxStackDepth);
    }

    @Override
    public Optional<PendingAction> getPendingAction(String conversationId, String ownerId) {
        return peekPendingAction(conversationId, ownerId);
    }

    @Override
    public void savePendingAction(String conversationId, String ownerId, PendingAction pendingAction) {
        // Legacy API: treat as replace (clear then push).
        clearPendingActions(conversationId, ownerId);
        pushPendingAction(conversationId, ownerId, pendingAction);
    }

    @Override
    public Optional<PendingAction> peekPendingAction(String conversationId, String ownerId) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(ownerId)) {
            return Optional.empty();
        }

        return storageProvider.findById(conversationId)
            .filter(session -> session != null && session.isOwnedBy(ownerId))
            .map(ChatSession::getSessionMetadata)
            .map(metadata -> metadata != null ? ConfirmationStack.peek(metadata) : null)
            .filter(action -> action != null);
    }

    @Override
    public void pushPendingAction(String conversationId, String ownerId, PendingAction pendingAction) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(ownerId) || pendingAction == null) {
            return;
        }

        storageProvider.findById(conversationId)
            .filter(session -> session != null && session.isOwnedBy(ownerId))
            .ifPresent(session -> {
                Map<String, Object> metadata = session.getSessionMetadata();
                Map<String, Object> mutable = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
                ConfirmationStack.push(mutable, pendingAction, maxStackDepth);
                session.setSessionMetadata(mutable);
                storageProvider.save(session);
            });
    }

    @Override
    public Optional<PendingAction> popPendingAction(String conversationId, String ownerId) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(ownerId)) {
            return Optional.empty();
        }

        return storageProvider.findById(conversationId)
            .filter(session -> session != null && session.isOwnedBy(ownerId))
            .map(session -> {
                Map<String, Object> metadata = session.getSessionMetadata();
                if (metadata == null) {
                    return null;
                }
                Map<String, Object> mutable = new HashMap<>(metadata);
                PendingAction popped = ConfirmationStack.pop(mutable);
                session.setSessionMetadata(mutable);
                storageProvider.save(session);
                return popped;
            })
            .filter(action -> action != null);
    }

    @Override
    public void clearPendingAction(String conversationId, String ownerId) {
        clearPendingActions(conversationId, ownerId);
    }

    @Override
    public void clearPendingActions(String conversationId, String ownerId) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(ownerId)) {
            return;
        }

        storageProvider.findById(conversationId)
            .filter(session -> session != null && session.isOwnedBy(ownerId))
            .ifPresent(session -> {
                Map<String, Object> metadata = session.getSessionMetadata();
                if (metadata == null || metadata.isEmpty()) {
                    return;
                }
                Map<String, Object> mutable = new HashMap<>(metadata);
                ConfirmationStack.clear(mutable);
                session.setSessionMetadata(mutable);
                storageProvider.save(session);
            });
    }

    @Override
    public List<PendingAction> getPendingActionStack(String conversationId, String ownerId) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(ownerId)) {
            return List.of();
        }

        return storageProvider.findById(conversationId)
            .filter(session -> session != null && session.isOwnedBy(ownerId))
            .map(ChatSession::getSessionMetadata)
            .map(metadata -> metadata != null ? ConfirmationStack.getAll(metadata) : List.<PendingAction>of())
            .orElse(List.of());
    }

    @Override
    public void replacePendingActionStack(String conversationId, String ownerId, List<PendingAction> stackTopFirst) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(ownerId)) {
            return;
        }

        storageProvider.findById(conversationId)
            .filter(session -> session != null && session.isOwnedBy(ownerId))
            .ifPresent(session -> {
                Map<String, Object> metadata = session.getSessionMetadata();
                Map<String, Object> mutable = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
                ConfirmationStack.replace(mutable, stackTopFirst, maxStackDepth);
                session.setSessionMetadata(mutable);
                storageProvider.save(session);
            });
    }
}
