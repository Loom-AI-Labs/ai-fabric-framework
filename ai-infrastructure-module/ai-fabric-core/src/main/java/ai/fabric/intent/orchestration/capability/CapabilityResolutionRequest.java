package ai.fabric.intent.orchestration.capability;

import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import java.util.Collection;
import java.util.Set;

/**
 * Inputs to deterministic effective capability resolution.
 */
public record CapabilityResolutionRequest(
    RequestedCapabilityProfile requestedProfile,
    OrchestrationPolicy orchestrationPolicy,
    Collection<AIActionMetaData> registeredActions,
    Set<String> registeredVectorSpaces,
    Set<String> deploymentAllowedActions,
    Set<String> authorityAllowedActions,
    TrustedExecutionContext trustedExecutionContext
) {
}
