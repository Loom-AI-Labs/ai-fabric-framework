package ai.fabric.chat.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcConversationControlStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");

    private ConversationScope scope;
    private DefaultConversationControlGateway gateway;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:conversation-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        JdbcConversationControlStore store = new JdbcConversationControlStore(
            dataSource,
            new ObjectMapper(),
            true
        );
        gateway = new DefaultConversationControlGateway(
            store,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        scope = new ConversationScope("tenant-1", "deployment-1", "conversation-1", "owner-1");
    }

    @Test
    void transitionsAreAtomicIdempotentAndInvalidateAiLease() {
        ConversationControlSnapshot initial = gateway.status(scope);
        ConversationTurnLease lease = gateway.acquireAiTurnLease(scope, Duration.ofMinutes(2));
        ConversationControlCommand request = command(
            ConversationControlCommandType.REQUEST_HUMAN,
            initial.version(),
            "request-1",
            "handover-1",
            "channel-1"
        );

        ConversationControlResult applied = gateway.transition(request);
        ConversationControlResult replay = gateway.transition(request);

        assertThat(applied.outcome()).isEqualTo(ConversationControlOutcome.APPLIED);
        assertThat(applied.snapshot().state()).isEqualTo(ConversationControlState.HUMAN_PENDING);
        assertThat(applied.snapshot().controlEpoch()).isEqualTo(1);
        assertThat(replay.outcome()).isEqualTo(ConversationControlOutcome.IDEMPOTENT_REPLAY);
        assertThat(replay.snapshot()).isEqualTo(applied.snapshot());
        assertThatThrownBy(() -> gateway.verifyAiTurnLease(lease))
            .isInstanceOf(ConversationControlViolationException.class)
            .extracting(ex -> ((ConversationControlViolationException) ex).code())
            .isEqualTo("AI_TURN_LEASE_STALE");
    }

    @Test
    void staleVersionCannotWinASecondOwnershipTransition() {
        gateway.status(scope);
        ConversationControlResult first = gateway.transition(command(
            ConversationControlCommandType.REQUEST_HUMAN,
            0,
            "request-a",
            "handover-a",
            "channel-1"
        ));
        ConversationControlResult stale = gateway.transition(command(
            ConversationControlCommandType.REQUEST_HUMAN,
            0,
            "request-b",
            "handover-b",
            "channel-2"
        ));

        assertThat(first.outcome()).isEqualTo(ConversationControlOutcome.APPLIED);
        assertThat(stale.outcome()).isEqualTo(ConversationControlOutcome.VERSION_CONFLICT);
        assertThat(stale.snapshot().activeHandoverId()).isEqualTo("handover-a");
    }

    @Test
    void participantMessagesAreOrderedAndRoleGated() {
        gateway.status(scope);
        ConversationTurnLease lease = gateway.acquireAiTurnLease(scope, Duration.ofMinutes(2));
        gateway.appendMessage(message("message-1", "client-1", ConversationParticipantRole.USER, "Hello"), null);
        gateway.appendMessage(message("message-2", "client-2", ConversationParticipantRole.AI, "Hi"), lease);

        ConversationControlResult pending = gateway.transition(command(
            ConversationControlCommandType.REQUEST_HUMAN,
            0,
            "request-1",
            "handover-1",
            "channel-1"
        ));
        ConversationControlResult active = gateway.transition(command(
            ConversationControlCommandType.ACTIVATE_HUMAN,
            pending.snapshot().version(),
            "claim-1",
            null,
            null
        ));
        gateway.appendMessage(message("message-3", "provider-1", ConversationParticipantRole.HUMAN, "I can help"), null);

        assertThat(active.snapshot().state()).isEqualTo(ConversationControlState.HUMAN_ACTIVE);
        assertThat(gateway.messages(scope, 0, 20))
            .extracting(ConversationMessage::role)
            .containsExactly(
                ConversationParticipantRole.USER,
                ConversationParticipantRole.AI,
                ConversationParticipantRole.HUMAN
            );
        assertThatThrownBy(() -> gateway.appendMessage(
            message("message-4", "client-4", ConversationParticipantRole.AI, "Late response"),
            lease
        )).isInstanceOf(ConversationControlViolationException.class);
    }

    @Test
    void differentOwnerCannotReadOrMutateConversation() {
        gateway.status(scope);
        ConversationScope forged = new ConversationScope(
            scope.tenantId(), scope.deploymentId(), scope.conversationId(), "owner-2"
        );

        assertThatThrownBy(() -> gateway.status(forged))
            .isInstanceOf(ConversationControlViolationException.class)
            .extracting(ex -> ((ConversationControlViolationException) ex).code())
            .isEqualTo("CONVERSATION_OWNER_MISMATCH");
    }

    @Test
    void commandIdempotencyRejectsChangedPayload() {
        gateway.status(scope);
        gateway.transition(command(
            ConversationControlCommandType.REQUEST_HUMAN,
            0,
            "request-1",
            "handover-1",
            "channel-1"
        ));

        ConversationControlCommand changed = new ConversationControlCommand(
            scope,
            ConversationControlCommandType.REQUEST_HUMAN,
            0,
            "request-1",
            "actor-1",
            "CHANGED_REASON",
            "handover-1",
            "channel-1"
        );

        assertThatThrownBy(() -> gateway.transition(changed))
            .isInstanceOf(ConversationControlViolationException.class)
            .extracting(ex -> ((ConversationControlViolationException) ex).code())
            .isEqualTo("CONTROL_COMMAND_IDEMPOTENCY_CONFLICT");
    }

    @Test
    void messageIdempotencyRejectsChangedPayload() {
        gateway.status(scope);
        gateway.appendMessage(message(
            "message-1",
            "client-1",
            ConversationParticipantRole.USER,
            "Original"
        ), null);

        assertThatThrownBy(() -> gateway.appendMessage(message(
            "message-1",
            "client-1",
            ConversationParticipantRole.USER,
            "Changed"
        ), null))
            .isInstanceOf(ConversationControlViolationException.class)
            .extracting(ex -> ((ConversationControlViolationException) ex).code())
            .isEqualTo("MESSAGE_IDEMPOTENCY_CONFLICT");
    }

    private ConversationControlCommand command(
        ConversationControlCommandType type,
        long version,
        String idempotencyKey,
        String handoverId,
        String channelBindingId
    ) {
        return new ConversationControlCommand(
            scope, type, version, idempotencyKey, "actor-1", "TEST",
            handoverId, channelBindingId
        );
    }

    private ConversationMessageCommand message(
        String messageId,
        String clientMessageId,
        ConversationParticipantRole role,
        String content
    ) {
        return new ConversationMessageCommand(
            scope, messageId, clientMessageId, role, "actor-1", content, Map.of()
        );
    }
}
