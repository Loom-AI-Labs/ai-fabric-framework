package ai.fabric.chat.control;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Generic application service for conversation authority and participant messages. */
public interface ConversationControlGateway {

    ConversationControlSnapshot status(ConversationScope scope);

    ConversationControlResult transition(ConversationControlCommand command);

    ConversationTurnLease acquireAiTurnLease(ConversationScope scope, Duration ttl);

    void verifyAiTurnLease(ConversationTurnLease lease);

    ConversationMessage appendMessage(ConversationMessageCommand command, ConversationTurnLease aiLease);

    List<ConversationMessage> messages(ConversationScope scope, long afterSequence, int limit);

    Optional<ConversationOrchestrationState> orchestrationState(
        ConversationScope scope,
        long expectedControlEpoch
    );

    ConversationOrchestrationState updateOrchestrationState(
        ConversationScope scope,
        long expectedControlEpoch,
        UnaryOperator<Map<String, Object>> mutation
    );

    void clearOrchestrationState(ConversationScope scope, long expectedControlEpoch);
}
