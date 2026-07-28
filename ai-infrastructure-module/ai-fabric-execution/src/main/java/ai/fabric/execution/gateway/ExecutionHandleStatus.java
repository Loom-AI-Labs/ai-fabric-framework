package ai.fabric.execution.gateway;

public enum ExecutionHandleStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    REJECTED,
    EXPIRED
}
