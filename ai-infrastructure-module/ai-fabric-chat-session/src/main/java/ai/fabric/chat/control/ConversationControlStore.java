package ai.fabric.chat.control;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

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

    Optional<ConversationOrchestrationState> findOrchestrationState(
        ConversationScope scope,
        long expectedControlEpoch
    );

    ConversationOrchestrationState updateOrchestrationState(
        ConversationScope scope,
        long expectedControlEpoch,
        UnaryOperator<Map<String, Object>> mutation,
        Instant now
    );

    void clearOrchestrationState(ConversationScope scope, long expectedControlEpoch);
}
