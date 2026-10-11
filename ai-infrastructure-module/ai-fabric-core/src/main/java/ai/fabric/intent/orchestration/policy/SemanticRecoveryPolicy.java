package ai.fabric.intent.orchestration.policy;

import ai.fabric.config.OrchestrationProperties;

import java.util.Objects;

/**
 * Server-owned policy for recoveries that can change an LLM semantic decision.
 */
public record SemanticRecoveryPolicy(
    OrchestrationProperties.ExtractionFailureRecovery extractionFailure,
    OrchestrationProperties.MissingTargetRecovery missingTarget,
    OrchestrationProperties.MissingVectorSpaceRecovery missingVectorSpace,
    OrchestrationProperties.InvalidVectorSpaceRecovery invalidVectorSpace,
    OrchestrationProperties.UnknownActionRecovery unknownAction,
    OrchestrationProperties.ActionDomainMismatchRecovery actionDomainMismatch,
    OrchestrationProperties.AdvancedNoEvidenceRecovery advancedNoEvidence
) {
    public SemanticRecoveryPolicy {
        extractionFailure = Objects.requireNonNullElse(
            extractionFailure,
            OrchestrationProperties.ExtractionFailureRecovery.LLM_REPAIR_THEN_FAIL
        );
        missingTarget = Objects.requireNonNullElse(
            missingTarget,
            OrchestrationProperties.MissingTargetRecovery.LLM_REPAIR_THEN_CLARIFY
        );
        missingVectorSpace = Objects.requireNonNullElse(
            missingVectorSpace,
            OrchestrationProperties.MissingVectorSpaceRecovery.LLM_REPAIR_THEN_CLARIFY
        );
        invalidVectorSpace = Objects.requireNonNullElse(
            invalidVectorSpace,
            OrchestrationProperties.InvalidVectorSpaceRecovery.LLM_REPAIR_THEN_FAIL
        );
        unknownAction = Objects.requireNonNullElse(
            unknownAction,
            OrchestrationProperties.UnknownActionRecovery.LLM_REPAIR_THEN_FAIL
        );
        actionDomainMismatch = Objects.requireNonNullElse(
            actionDomainMismatch,
            OrchestrationProperties.ActionDomainMismatchRecovery.DENY_AND_LLM_REPLAN
        );
        advancedNoEvidence = Objects.requireNonNullElse(
            advancedNoEvidence,
            OrchestrationProperties.AdvancedNoEvidenceRecovery.RETURN_NO_EVIDENCE
        );
    }

    public static SemanticRecoveryPolicy strictDefaults() {
        return new SemanticRecoveryPolicy(null, null, null, null, null, null, null);
    }
}
