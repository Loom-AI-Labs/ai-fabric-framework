package ai.fabric.intent.action.invocation;

/**
 * Stable final-boundary action outcome.
 */
public enum GovernedActionInvocationStatus {
    EXECUTED,
    CONFIRMATION_REQUIRED,
    DENIED,
    INVALID,
    FAILED
}
