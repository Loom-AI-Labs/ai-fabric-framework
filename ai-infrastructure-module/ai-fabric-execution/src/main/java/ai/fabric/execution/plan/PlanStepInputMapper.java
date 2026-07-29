package ai.fabric.execution.plan;

import java.util.Map;

/**
 * Registered deterministic projection from plan state to one specialist input.
 */
public interface PlanStepInputMapper<P, I> {

    PlanComponentId id();

    Class<P> planInputType();

    Class<I> stepInputType();

    /**
     * Predecessor outputs visible to this mapper, keyed by plan step ID.
     */
    default Map<String, Class<?>> requiredStepOutputs() {
        return Map.of();
    }

    I map(P planInput, PlanStepOutputs approvedOutputs);
}
