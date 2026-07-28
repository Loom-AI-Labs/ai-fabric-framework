package ai.fabric.execution.context;

/**
 * Identifies the trusted runtime entry point that initiated orchestration.
 */
public enum ExecutionSource {
    INTERACTIVE,
    APPLICATION,
    EVENT,
    SCHEDULED
}
