package ai.fabric.execution.manager;

import java.util.List;
import java.util.Optional;

public interface ConversationManagerRegistry {

    Optional<RegisteredConversationManager> find(
        ConversationManagerId id
    );

    List<RegisteredConversationManager> list();

    default RegisteredConversationManager require(
        ConversationManagerId id
    ) {
        return find(id).orElseThrow(() ->
            new ConversationManagerNotFoundException(
                "No conversation manager is registered for " + id
            )
        );
    }

    final class ConversationManagerNotFoundException
        extends RuntimeException {

        public ConversationManagerNotFoundException(String message) {
            super(message);
        }
    }
}
