package ai.fabric.chat.control;

import java.time.Duration;
import java.util.List;

/** Generic application service for conversation authority and participant messages. */
public interface ConversationControlGateway {

    ConversationControlSnapshot status(ConversationScope scope);

    ConversationControlResult transition(ConversationControlCommand command);

    ConversationTurnLease acquireAiTurnLease(ConversationScope scope, Duration ttl);

    void verifyAiTurnLease(ConversationTurnLease lease);

    ConversationMessage appendMessage(ConversationMessageCommand command, ConversationTurnLease aiLease);

    List<ConversationMessage> messages(ConversationScope scope, long afterSequence, int limit);
}
