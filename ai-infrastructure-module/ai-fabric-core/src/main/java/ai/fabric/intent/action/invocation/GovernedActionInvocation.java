package ai.fabric.intent.action.invocation;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Complete input to the non-bypassable governed action boundary.
 */
public record GovernedActionInvocation(
    String actionName,
    Map<String, Object> parameters,
    ActionContext actionContext,
    TrustedExecutionContext trustedExecutionContext,
    EffectiveCapabilityProfile effectiveCapabilityProfile,
    ActionConfirmationState confirmationState,
    List<AIEvidenceReference> trustedEvidence
) {
    public GovernedActionInvocation {
        actionName = Objects.requireNonNull(actionName, "actionName is required").trim();
        if (actionName.isEmpty()) {
            throw new IllegalArgumentException("actionName is required");
        }
        parameters = parameters == null || parameters.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        Objects.requireNonNull(actionContext, "actionContext is required");
        Objects.requireNonNull(effectiveCapabilityProfile, "effectiveCapabilityProfile is required");
        confirmationState = confirmationState == null
            ? ActionConfirmationState.NOT_CONFIRMED
            : confirmationState;
        trustedEvidence = trustedEvidence == null ? List.of() : List.copyOf(trustedEvidence);
    }
}
