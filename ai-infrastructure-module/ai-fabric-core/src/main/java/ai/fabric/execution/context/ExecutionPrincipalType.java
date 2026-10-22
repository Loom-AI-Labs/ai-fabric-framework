package ai.fabric.execution.context;

/**
 * Type of verified principal initiating an orchestration request.
 */
public enum ExecutionPrincipalType {
    END_USER,
    ANONYMOUS_SESSION,
    SERVICE,
    SYSTEM
}
