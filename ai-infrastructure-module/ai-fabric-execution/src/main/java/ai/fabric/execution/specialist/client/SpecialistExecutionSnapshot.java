package ai.fabric.execution.specialist.client;

import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.ExecutionHandle;
import java.util.Objects;

/**
 * Typed application view of one asynchronous specialist execution.
 */
public record SpecialistExecutionSnapshot<O>(
    ExecutionHandle handle,
    AIExecutionResult<O> result
) {
    public SpecialistExecutionSnapshot {
        Objects.requireNonNull(handle, "handle is required");
    }
}
