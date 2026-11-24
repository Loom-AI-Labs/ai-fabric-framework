package ai.fabric.execution.gateway;

public enum AIExecutionStatus {
    SUCCEEDED,
    YIELDED,
    PAUSED,
    CONFIRMATION_REQUIRED,
    WAITING_FOR_INPUT,
    FAILED,
    DENIED,
    INVALID,
    DEADLINE_EXCEEDED,
    CANCELLED
}
