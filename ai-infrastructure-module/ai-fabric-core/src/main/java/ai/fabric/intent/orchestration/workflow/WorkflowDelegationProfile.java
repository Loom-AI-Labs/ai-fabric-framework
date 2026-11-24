package ai.fabric.intent.orchestration.workflow;

import java.util.List;

/** A pinned published profile supplied by the trusted host, not by the model. */
public record WorkflowDelegationProfile(String profileId, String definitionFingerprint, String instructions,
                                        List<String> allowedActions) {
    public WorkflowDelegationProfile {
        allowedActions = List.copyOf(allowedActions);
        if (profileId == null || profileId.isBlank() || definitionFingerprint == null || definitionFingerprint.isBlank()
            || instructions == null || instructions.isBlank() || instructions.length() > 32000
            || allowedActions.size() > 256 || allowedActions.stream().anyMatch(s -> s == null || s.isBlank()))
            throw new IllegalArgumentException("Invalid published delegation profile");
    }
}
