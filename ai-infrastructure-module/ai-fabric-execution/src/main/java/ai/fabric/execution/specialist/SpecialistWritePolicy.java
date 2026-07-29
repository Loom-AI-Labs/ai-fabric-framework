package ai.fabric.execution.specialist;

/**
 * Application-owned write policy for a specialist definition.
 */
public enum SpecialistWritePolicy {
    DISABLED,
    CONFIRMATION_RECEIPT_REQUIRED;

    public boolean permitsProposals() {
        return this == CONFIRMATION_RECEIPT_REQUIRED;
    }
}
