package ai.fabric.chat.control;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.control.OrchestrationControlException;
import ai.fabric.intent.orchestration.control.OrchestrationTurnGuard;
import java.util.Objects;

/** Adapts a durable conversation lease to the framework orchestration guard. */
public final class ConversationTurnLeaseGuard implements OrchestrationTurnGuard {

    private final ConversationControlGateway gateway;
    private final ConversationTurnLease lease;

    public ConversationTurnLeaseGuard(
        ConversationControlGateway gateway,
        ConversationTurnLease lease
    ) {
        this.gateway = Objects.requireNonNull(gateway, "gateway is required");
        this.lease = Objects.requireNonNull(lease, "lease is required");
    }

    @Override
    public void verifyBeforeOrchestration() {
        verify();
    }

    @Override
    public void verifyBeforeAction(AIActionMetaData metadata, ActionContext context) {
        verify();
    }

    @Override
    public void verifyBeforeResultCommit() {
        verify();
    }

    @Override
    public void verifyBeforeResultCommit(OrchestrationResult result) {
        try {
            verify();
        } catch (OrchestrationControlException exception) {
            if (isIntentionalTerminalTransfer(result, exception)) {
                return;
            }
            throw exception;
        }
    }

    private boolean isIntentionalTerminalTransfer(
        OrchestrationResult result,
        OrchestrationControlException exception
    ) {
        return "AI_TURN_LEASE_STALE".equals(exception.code())
            && result != null
            && result.isSuccess()
            && result.getType() == OrchestrationResultType.ACTION_EXECUTED
            && result.getMetadata() != null
            && Boolean.TRUE.equals(result.getMetadata().get("terminalAction"));
    }

    private void verify() {
        try {
            gateway.verifyAiTurnLease(lease);
        } catch (ConversationControlViolationException ex) {
            throw new OrchestrationControlException(ex.code(), ex.getMessage());
        }
    }
}
