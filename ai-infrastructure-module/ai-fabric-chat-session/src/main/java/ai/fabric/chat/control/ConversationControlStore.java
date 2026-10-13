package ai.fabric.chat.control;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for control state, command idempotency, and messages. */
public interface ConversationControlStore {

    ConversationControlSnapshot getOrCreate(ConversationScope scope, Instant now);

    Optional<ConversationControlSnapshot> find(ConversationScope scope);

    ConversationControlResult applyTransition(
        ConversationControlCommand command,
        ConversationControlState requiredState,
        ConversationControlState targetState,
        boolean advanceEpoch,
        Instant now
    );

    ConversationMessage appendMessage(ConversationMessageCommand command, Instant now);

    List<ConversationMessage> listMessages(ConversationScope scope, long afterSequence, int limit);
}
