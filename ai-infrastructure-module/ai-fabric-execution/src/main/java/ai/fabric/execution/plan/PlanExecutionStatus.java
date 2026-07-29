package ai.fabric.execution.plan;

public enum PlanExecutionStatus {
    RUNNING,
    SUCCEEDED,
    WAITING_FOR_INPUT,
    FAILED,
    DENIED,
    INVALID,
    DEADLINE_EXCEEDED,
    CANCELLED
}
