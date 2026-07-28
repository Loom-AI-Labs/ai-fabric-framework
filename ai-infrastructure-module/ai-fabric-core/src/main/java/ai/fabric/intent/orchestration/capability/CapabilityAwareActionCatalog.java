package ai.fabric.intent.orchestration.capability;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import java.util.List;
import java.util.Optional;

/**
 * Restricts action discovery and invocation to an effective capability profile.
 */
public final class CapabilityAwareActionCatalog {

    private final AIActionRegistry actionRegistry;

    public CapabilityAwareActionCatalog(AIActionRegistry actionRegistry) {
        this.actionRegistry = java.util.Objects.requireNonNull(actionRegistry, "actionRegistry is required");
    }

    public List<AIActionMetaData> listVisibleActions(EffectiveCapabilityProfile profile) {
        requireProfile(profile);
        return actionRegistry.getAllMetadata().stream()
            .filter(metadata -> profile.isActionVisible(metadata.getName()))
            .toList();
    }

    public Optional<AIActionMetaData> findVisibleAction(
        String actionName,
        EffectiveCapabilityProfile profile
    ) {
        requireProfile(profile);
        if (!profile.isActionVisible(actionName)) {
            return Optional.empty();
        }
        return actionRegistry.findMetadata(actionName);
    }

    public AIActionMetaData requireExecutableAction(
        String actionName,
        EffectiveCapabilityProfile profile
    ) {
        requireProfile(profile);
        AIActionMetaData metadata = findVisibleAction(actionName, profile)
            .orElseThrow(() -> new CapabilityDeniedException(
                "ACTION_NOT_IN_EFFECTIVE_PROFILE",
                "Action is not available in the effective capability profile"
            ));
        boolean allowed = metadata.getAccessMode() != null
            && (metadata.getAccessMode().isReadOnly()
                ? profile.canExecuteReadAction(actionName)
                : profile.canProposeWriteAction(actionName));
        if (!allowed) {
            throw new CapabilityDeniedException(
                "ACTION_NOT_EXECUTABLE",
                "Action is visible but cannot execute in the effective capability profile"
            );
        }
        return metadata;
    }

    private void requireProfile(EffectiveCapabilityProfile profile) {
        if (profile == null) {
            throw new CapabilityDeniedException(
                "EFFECTIVE_PROFILE_REQUIRED",
                "An effective capability profile is required"
            );
        }
    }

    public static final class CapabilityDeniedException extends RuntimeException {
        private final String code;

        public CapabilityDeniedException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
