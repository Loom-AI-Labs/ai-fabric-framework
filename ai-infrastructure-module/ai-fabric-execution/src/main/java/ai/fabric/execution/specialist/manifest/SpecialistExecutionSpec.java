package ai.fabric.execution.specialist.manifest;

import ai.fabric.execution.specialist.ExecutionStrategy;
import ai.fabric.execution.specialist.SpecialistWritePolicy;

public record SpecialistExecutionSpec(
    ExecutionStrategy strategy,
    SpecialistWritePolicy writePolicy
) {}
