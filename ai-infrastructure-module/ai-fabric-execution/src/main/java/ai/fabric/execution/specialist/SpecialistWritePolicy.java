package ai.fabric.execution.specialist;

/**
 * Application-owned write policy for a specialist definition.
 */
public enum SpecialistWritePolicy {
    DISABLED,
    /** Follow each action's existing confirmation policy through governed invocation. */
    ACTION_POLICY,
    CONFIRMATION_RECEIPT_REQUIRED;

    public boolean permitsProposals() {
        return this == CONFIRMATION_RECEIPT_REQUIRED || this == ACTION_POLICY;
    }
}
