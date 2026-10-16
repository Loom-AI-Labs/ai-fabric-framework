package ai.fabric.chat.storage;

import ai.fabric.chat.domain.ChatSession;
import ai.fabric.chat.repository.ChatSessionRepository;
import ai.fabric.chat.spi.ChatSessionStorageProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.transaction.annotation.Transactional;

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

    @Override
    @Transactional
    public <T> Optional<T> mutateOwnedSession(
        String conversationId,
        String ownerId,
        Function<ChatSession, T> mutation
    ) {
        if (mutation == null) {
            throw new IllegalArgumentException("mutation is required");
        }
        Optional<ChatSession> owned = repository.findByIdForUpdate(conversationId)
            .filter(session -> session != null && session.isOwnedBy(ownerId));
        if (owned.isEmpty()) {
            return Optional.empty();
        }
        ChatSession session = owned.get();
        T result = mutation.apply(session);
        repository.save(session);
        return Optional.ofNullable(result);
    }
}
