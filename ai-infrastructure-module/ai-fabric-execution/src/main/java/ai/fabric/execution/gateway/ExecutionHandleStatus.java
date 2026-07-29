package ai.fabric.execution.gateway;

public enum ExecutionHandleStatus {
    QUEUED,
    RUNNING,
    WAITING_FOR_INPUT,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    REJECTED,
    EXPIRED
}
