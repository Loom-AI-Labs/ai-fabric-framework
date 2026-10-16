package ai.fabric.chat.spi;

import ai.fabric.chat.domain.ChatSession;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

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

    /**
     * Mutates one owner-verified session under the provider's atomic write boundary.
     *
     * <p>Providers that back confirmation state must implement this operation so a
     * compare-and-consume decision cannot remove a different pending action.</p>
     */
    default <T> Optional<T> mutateOwnedSession(
        String conversationId,
        String ownerId,
        Function<ChatSession, T> mutation
    ) {
        throw new IllegalStateException(
            "Atomic owned-session mutation is required for confirmation state"
        );
    }
}

