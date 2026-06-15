package ai.fabric.intent.action;

/**
 * Stable side-effect posture derived from an action contract.
 */
public enum ActionSideEffectLevel {
    NONE,
    OPTIONAL_MUTATION,
    MUTATING;

    public static ActionSideEffectLevel fromAccessMode(ActionAccessMode accessMode) {
        if (accessMode == null) {
            return NONE;
        }
        return switch (accessMode) {
            case READ -> NONE;
            case READ_WRITE -> OPTIONAL_MUTATION;
            case WRITE_ONLY -> MUTATING;
        };
    }
}
