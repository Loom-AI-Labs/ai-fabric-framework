package ai.fabric.execution.gateway;

public enum AIExecutionStatus {
    SUCCEEDED,
    CONFIRMATION_REQUIRED,
    FAILED,
    DENIED,
    INVALID,
    DEADLINE_EXCEEDED,
    CANCELLED
}
