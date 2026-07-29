package ai.fabric.execution.plan;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Immutable composition blueprint. It orders work but grants no authority.
 */
public record ExecutionPlanDefinition<I, O>(
    ExecutionPlanId id,
    Class<I> inputType,
    Class<O> outputType,
    List<SpecialistPlanStep> steps,
    PlanComponentId aggregatorId,
    Duration maximumDuration
) {
    public ExecutionPlanDefinition {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(inputType, "inputType is required");
        Objects.requireNonNull(outputType, "outputType is required");
        steps = steps == null ? List.of() : List.copyOf(steps);
        Objects.requireNonNull(aggregatorId, "aggregatorId is required");
        if (maximumDuration == null
            || maximumDuration.isZero()
            || maximumDuration.isNegative()) {
            throw new IllegalArgumentException(
                "maximumDuration must be positive"
            );
        }
    }
}
