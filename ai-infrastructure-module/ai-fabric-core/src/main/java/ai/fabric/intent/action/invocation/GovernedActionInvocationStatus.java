package ai.fabric.intent.action.invocation;

/**
 * Stable final-boundary action outcome.
 */
public enum GovernedActionInvocationStatus {
    EXECUTED,
    CONFIRMATION_REQUIRED,
    DENIED,
    INVALID,
    FAILED,
    /**
     * The handler may have produced a side effect before an exception made the
     * authoritative result unavailable. Callers must reconcile, never retry
     * blindly.
     */
    OUTCOME_UNKNOWN
}
