package ai.fabric.intent.action.invocation;

/**
 * Trusted confirmation state supplied to the final action boundary.
 */
public enum ActionConfirmationState {
    NOT_CONFIRMED,
    CONFIRMED,
    /** Check whether a proposal is still required; never dispatch the handler. */
    PROPOSAL_PREFLIGHT
}
