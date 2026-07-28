package ai.fabric.execution.specialist;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.intent.orchestration.OrchestrationResult;
import java.util.List;

/**
 * Projects a successful orchestration result into a validated application contract.
 */
public interface SpecialistOutputAdapter<O> {

    Class<O> outputType();

    /**
     * Selects deterministic projection or an explicit structured generation stage.
     */
    default SpecialistOutputMode outputMode() {
        return SpecialistOutputMode.DIRECT_PROJECTION;
    }

    /**
     * Provider-facing contract used only by structured output finalization.
     */
    default String outputContractInstructions() {
        return null;
    }

    O project(OrchestrationResult result, List<AIEvidenceReference> evidence);

    /**
     * Validates that the approved orchestration result and evidence are sufficient to
     * produce this specialist's domain output.
     *
     * <p>This check runs before projection or structured generation. Implementations
     * should validate presence and provenance, not interpret untrusted prose.</p>
     */
    default void validateGrounding(
        OrchestrationResult result,
        List<AIEvidenceReference> evidence
    ) {
        // Most specialists do not require a stricter grounding contract.
    }

    void validate(O output);

    /**
     * Validates the final typed output against the approved source result and evidence.
     *
     * <p>The default preserves the ordinary schema validation contract. Specialists
     * with authoritative application facts can additionally reject semantically
     * inconsistent model projections here.</p>
     */
    default void validateFinalOutput(
        O output,
        OrchestrationResult sourceResult,
        List<AIEvidenceReference> evidence
    ) {
        validate(output);
    }

    /**
     * Projects an already validated output into its application-owned public form.
     *
     * <p>This hook runs only after {@link #validateFinalOutput(Object,
     * OrchestrationResult, List)} has accepted the provider output. It may
     * canonicalize user-facing text or remove fields that the application does not
     * expose, but it must not repair an invalid model decision or introduce facts
     * that are absent from the approved source result and evidence.</p>
     */
    default O normalizeFinalOutput(
        O output,
        OrchestrationResult sourceResult,
        List<AIEvidenceReference> evidence
    ) {
        return output;
    }

    /**
     * Returns safe assistant text to persist after output validation succeeds.
     */
    default String conversationOutput(O output, OrchestrationResult sourceResult) {
        return sourceResult != null ? sourceResult.getMessage() : null;
    }
}
