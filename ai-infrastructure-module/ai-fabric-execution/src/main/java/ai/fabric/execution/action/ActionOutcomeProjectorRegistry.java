package ai.fabric.execution.action;

import ai.fabric.intent.action.AIActionNames;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ActionOutcomeProjectorRegistry {

    private final Map<String, ActionOutcomeProjector> projectors;

    public ActionOutcomeProjectorRegistry(
        List<ActionOutcomeProjector> projectors
    ) {
        Map<String, ActionOutcomeProjector> validated = new LinkedHashMap<>();
        for (ActionOutcomeProjector projector :
            projectors != null ? projectors : List.<ActionOutcomeProjector>of()) {
            Objects.requireNonNull(projector, "projector must not be null");
            String action = AIActionNames.normalize(projector.actionName());
            if (validated.putIfAbsent(action, projector) != null) {
                throw new IllegalStateException(
                    "Duplicate action outcome projector for " + action
                );
            }
        }
        this.projectors = Map.copyOf(validated);
    }

    public ActionOutcomeProjector require(String actionName) {
        String normalized = AIActionNames.normalize(actionName);
        ActionOutcomeProjector projector = projectors.get(normalized);
        if (projector == null) {
            throw new ActionProposalValidationException(
                "ACTION_OUTCOME_PROJECTOR_REQUIRED",
                "A safe action outcome projector is not registered."
            );
        }
        return projector;
    }
}
