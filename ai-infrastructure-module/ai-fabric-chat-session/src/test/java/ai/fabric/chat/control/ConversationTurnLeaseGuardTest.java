package ai.fabric.chat.control;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.control.OrchestrationControlException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConversationTurnLeaseGuardTest {

    private final ConversationControlGateway gateway = mock(ConversationControlGateway.class);
    private final ConversationTurnLease lease = new ConversationTurnLease(
        new ConversationScope("tenant-1", "deployment-1", "conversation-1", "owner-1"),
        "turn-1",
        4,
        Instant.parse("2026-10-13T00:00:00Z"),
        Instant.parse("2026-10-13T00:05:00Z")
    );
    private final ConversationTurnLeaseGuard guard = new ConversationTurnLeaseGuard(gateway, lease);

    @Test
    void permitsSuccessfulTerminalResultAfterItsControlTransferInvalidatesTheLease() {
        staleLease("AI_TURN_LEASE_STALE");
        OrchestrationResult result = OrchestrationResult.builder()
            .type(OrchestrationResultType.ACTION_EXECUTED)
            .success(true)
            .metadata(Map.of("terminalAction", true))
            .build();

        assertThatCode(() -> guard.verifyBeforeResultCommit(result)).doesNotThrowAnyException();
    }

    @Test
    void rejectsStaleLeaseForOrdinaryResult() {
        staleLease("AI_TURN_LEASE_STALE");
        OrchestrationResult result = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .build();

        assertThatThrownBy(() -> guard.verifyBeforeResultCommit(result))
            .isInstanceOf(OrchestrationControlException.class)
            .extracting("code")
            .isEqualTo("AI_TURN_LEASE_STALE");
    }

    @Test
    void rejectsExpiredLeaseEvenForSuccessfulTerminalResult() {
        staleLease("AI_TURN_LEASE_EXPIRED");
        OrchestrationResult result = OrchestrationResult.builder()
            .type(OrchestrationResultType.ACTION_EXECUTED)
            .success(true)
            .metadata(Map.of("terminalAction", true))
            .build();

        assertThatThrownBy(() -> guard.verifyBeforeResultCommit(result))
            .isInstanceOf(OrchestrationControlException.class)
            .extracting("code")
            .isEqualTo("AI_TURN_LEASE_EXPIRED");
    }

    private void staleLease(String code) {
        doThrow(new ConversationControlViolationException(code, "denied"))
            .when(gateway)
            .verifyAiTurnLease(lease);
    }
}
