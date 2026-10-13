package ai.fabric.intent.orchestration.control;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.orchestration.OrchestrationResult;

/**
 * Host-owned lease guard for one interactive orchestration turn.
 *
 * <p>The framework invokes this boundary before starting orchestration, immediately
 * before a governed action executes, and before returning the completed result.
 * Implementations must fail closed when the durable control epoch no longer matches
 * the lease acquired for the turn.</p>
 */
public interface OrchestrationTurnGuard {

    void verifyBeforeOrchestration();

    void verifyBeforeAction(AIActionMetaData metadata, ActionContext context);

    void verifyBeforeResultCommit();

    /**
     * Verify authority immediately before returning a specific orchestration result.
     *
     * <p>The result-aware form lets a host distinguish an intentional, successful
     * terminal control transfer from an unrelated stale turn. Existing guards retain
     * fail-closed behavior through the default delegation.</p>
     */
    default void verifyBeforeResultCommit(OrchestrationResult result) {
        verifyBeforeResultCommit();
    }
}
