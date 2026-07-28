package ai.fabric.execution.specialist;

import java.util.Objects;

/**
 * Canonical immutable aggregate for one application-approved specialist.
 */
public record SpecialistDefinition<I, O>(
    SpecialistIdentity identity,
    SpecialistInstructions instructions,
    SpecialistExecutionProfile executionProfile,
    SpecialistLimits limits,
    SpecialistInputAdapter<I> inputAdapter,
    SpecialistOutputAdapter<O> outputAdapter
) {
    public SpecialistDefinition {
        Objects.requireNonNull(identity, "identity is required");
        Objects.requireNonNull(instructions, "instructions are required");
        Objects.requireNonNull(executionProfile, "executionProfile is required");
        Objects.requireNonNull(limits, "limits are required");
        Objects.requireNonNull(inputAdapter, "inputAdapter is required");
        Objects.requireNonNull(outputAdapter, "outputAdapter is required");
        Objects.requireNonNull(inputAdapter.inputType(), "inputAdapter.inputType is required");
        Objects.requireNonNull(outputAdapter.outputType(), "outputAdapter.outputType is required");
    }

    public SpecialistId id() {
        return identity.id();
    }
}
