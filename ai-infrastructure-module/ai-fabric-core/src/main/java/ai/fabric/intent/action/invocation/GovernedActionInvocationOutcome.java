package ai.fabric.intent.action.invocation;

import ai.fabric.intent.action.ActionResult;

/**
 * Result of final governed action validation and execution.
 */
public record GovernedActionInvocationOutcome(
    GovernedActionInvocationStatus status,
    ActionResult actionResult,
    ActionInvocationFailure publicFailure
) {
    public boolean executed() {
        return status == GovernedActionInvocationStatus.EXECUTED;
    }
}
