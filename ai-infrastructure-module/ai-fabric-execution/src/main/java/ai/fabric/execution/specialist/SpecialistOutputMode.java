package ai.fabric.execution.specialist;

/**
 * Determines how a successful orchestration result becomes an application output.
 */
public enum SpecialistOutputMode {
    /**
     * The application adapter deterministically projects the orchestration result.
     */
    DIRECT_PROJECTION,

    /**
     * AI Fabric performs one explicit structured generation over bounded,
     * policy-filtered grounding before validating the application output.
     */
    STRUCTURED_GENERATION
}
