package ai.fabric.intent.action;

/**
 * Declares the side-effect semantics of an action.
 *
 * <p>Greenfield: actions MUST declare their access mode explicitly so the framework can apply deterministic
 * safety/UX policies (confirmation, fallback behavior, retries) without relying on name heuristics.</p>
 */
public enum ActionAccessMode {
    /**
     * Read-only action with no side effects.
     */
    READ,

    /**
     * Action that both reads and writes state.
     */
    READ_WRITE,

    /**
     * Write-only action that changes state (may still return data, but is considered side-effecting).
     */
    WRITE_ONLY

    ;

    /**
     * Whether this mode is safe to treat as a pure read helper.
     */
    public boolean isReadOnly() {
        return this == READ;
    }

    /**
     * Whether actions with this mode should be grounding-eligible without an explicit opt-in.
     */
    public boolean isGroundingEligibleByDefault() {
        return isReadOnly();
    }
}
