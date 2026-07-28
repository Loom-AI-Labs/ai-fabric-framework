package ai.fabric.intent.orchestration.capability;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.orchestration.OrchestrationContext;
import java.util.List;

/**
 * Filters model-facing action metadata using the server-resolved effective profile.
 */
public final class CapabilityAwareActionMetadataSupport {

    private CapabilityAwareActionMetadataSupport() {
    }

    public static List<AIActionMetaData> visibleActions(
        AIActionRegistry registry,
        OrchestrationContext context
    ) {
        if (registry == null) {
            return List.of();
        }
        EffectiveCapabilityProfile profile = context != null
            ? context.getEffectiveCapabilityProfile()
            : null;
        if (profile == null) {
            return registry.getAllMetadata();
        }
        return new CapabilityAwareActionCatalog(registry).listVisibleActions(profile);
    }
}
