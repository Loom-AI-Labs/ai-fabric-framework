package ai.fabric.intent.orchestration.information;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.capability.CapabilityAwareActionMetadataSupport;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import java.util.Locale;
import org.springframework.util.StringUtils;

/** Shared structural eligibility for READ planning, not authorization to execute. */
public final class ReadActionEligibilitySupport {
    private ReadActionEligibilitySupport() {
    }

    public static boolean hasQualifiedReadPath(AIActionRegistry registry, OrchestrationContext context) {
        if (registry == null || context == null) {
            return false;
        }
        var effective = context.getEffectiveCapabilityProfile();
        var policy = context.getOrchestrationPolicy();
        if (effective == null && (policy == null || policy.capabilities() == null
            || !policy.capabilities().actionsEnabled())) {
            return false;
        }
        var readPolicy = policy != null ? policy.readActionResolutionPolicy()
            : effective != null ? effective.readActionResolutionPolicy() : null;
        if (readPolicy == null || !readPolicy.enabled()) {
            return false;
        }
        return CapabilityAwareActionMetadataSupport.visibleActions(registry, context).stream()
            .anyMatch(metadata -> eligibleForPlanning(metadata, context, readPolicy)
                && registry.findHandler(metadata.getName()).isPresent());
    }

    static boolean eligible(AIActionMetaData metadata,
                            OrchestrationContext context,
                            OrchestrationPolicy.ReadActionResolutionPolicy readPolicy,
                            boolean anonymous) {
        return eligibleForPlanning(metadata, context, readPolicy)
            && (!anonymous || metadata.isAnonymousAllowed());
    }

    static boolean eligibleForPlanning(AIActionMetaData metadata,
                                       OrchestrationContext context,
                                       OrchestrationPolicy.ReadActionResolutionPolicy readPolicy) {
        if (metadata == null || !StringUtils.hasText(metadata.getName())
            || readPolicy == null || !readPolicy.enabled()
            || metadata.getAccessMode() != ActionAccessMode.READ
            || !metadata.isReadActionResolutionEligible()
            || (readPolicy.requireGroundingEligible() && !metadata.isGroundingEligible())) {
            return false;
        }
        var effective = context != null ? context.getEffectiveCapabilityProfile() : null;
        if (effective != null && !effective.canExecuteReadAction(metadata.getName())) {
            return false;
        }
        String name = metadata.getName().trim().toLowerCase(Locale.ROOT);
        return (!readPolicy.requireAllowlist() && !readPolicy.hasAllowedReadActions())
            || readPolicy.allowedReadActions().contains(name);
    }
}
