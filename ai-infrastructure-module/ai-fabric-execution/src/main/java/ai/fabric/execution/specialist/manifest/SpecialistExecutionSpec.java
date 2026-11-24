package ai.fabric.execution.specialist.manifest;

import ai.fabric.execution.specialist.ExecutionStrategy;
import ai.fabric.execution.specialist.SpecialistWritePolicy;

public record SpecialistExecutionSpec(
    ExecutionStrategy strategy,
    SpecialistWritePolicy writePolicy,
    ai.fabric.intent.orchestration.workflow.WorkflowPolicy workflow
) {
    public SpecialistExecutionSpec(ExecutionStrategy strategy, SpecialistWritePolicy writePolicy) { this(strategy, writePolicy, null); }
}
