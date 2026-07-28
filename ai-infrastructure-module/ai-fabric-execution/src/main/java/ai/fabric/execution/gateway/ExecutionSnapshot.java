package ai.fabric.execution.gateway;

import java.util.Objects;

public record ExecutionSnapshot(
    ExecutionHandle handle,
    AIExecutionResult<?> result
) {
    public ExecutionSnapshot {
        Objects.requireNonNull(handle, "handle is required");
    }
}
