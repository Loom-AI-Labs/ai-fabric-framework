package ai.fabric.intent.orchestration.capability;

import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import java.util.Objects;

/**
 * Applies a pre-resolved effective capability profile to downstream orchestration policy.
 */
public final class EffectiveCapabilityPolicySupport {

    private EffectiveCapabilityPolicySupport() {
    }

    public static OrchestrationPolicy constrain(
        OrchestrationPolicy policy,
        EffectiveCapabilityProfile effective
    ) {
        Objects.requireNonNull(policy, "policy is required");
        Objects.requireNonNull(effective, "effective capability profile is required");

        OrchestrationPolicy.OrchestrationCapabilities source = policy.capabilities();
        boolean actionsEnabled = source.actionsEnabled()
            && !effective.visibleActions().isEmpty();
        boolean retrievalEnabled = source.retrievalEnabled()
            && effective.retrievalEnabled();
        OrchestrationPolicy.OrchestrationCapabilities capabilities =
            new OrchestrationPolicy.OrchestrationCapabilities(
                actionsEnabled,
                retrievalEnabled,
                retrievalEnabled && source.deepRetrievalEnabled(),
                false,
                source.exposeReadProbeFallbackAttempt(),
                actionsEnabled && source.actionsPreferred(),
                false,
                retrievalEnabled,
                retrievalEnabled && source.vectorSpaceSelectionRequired(),
                source.minimizeRagWhenPinnedTargetsCoverRequest(),
                retrievalEnabled && source.forceRetrievalWhenTargetsPresent(),
                retrievalEnabled && source.forceRetrievalConsiderStoredTargets(),
                actionsEnabled
                    && source.forceGroundingEligibleReadActionPostGeneration()
            );

        return new OrchestrationPolicy(
            policy.profile(),
            policy.mode(),
            policy.position(),
            policy.informationMode(),
            capabilities,
            effective.readActionResolutionPolicy(),
            effective.ragBudgets(),
            policy.responseGenerationBudgets()
        );
    }
}
