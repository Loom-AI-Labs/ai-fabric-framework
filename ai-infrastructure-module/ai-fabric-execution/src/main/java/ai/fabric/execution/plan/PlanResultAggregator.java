package ai.fabric.execution.plan;

import java.util.Map;

/**
 * Registered deterministic projection from validated step outputs to one plan result.
 */
public interface PlanResultAggregator<P, O> {

    PlanComponentId id();

    Class<P> planInputType();

    Class<O> outputType();

    /**
     * Step outputs visible to this aggregator, keyed by plan step ID.
     */
    Map<String, Class<?>> requiredStepOutputs();

    O aggregate(P planInput, PlanStepOutputs approvedOutputs);
}
