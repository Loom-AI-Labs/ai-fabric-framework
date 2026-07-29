package ai.fabric.execution.gateway;

import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import java.util.Objects;

/**
 * Process-local binding used to protect execution status and continuation operations.
 */
record ExecutionAccessBinding(
    String principalId,
    ai.fabric.execution.context.ExecutionPrincipalType principalType,
    String subjectType,
    String subjectId,
    ai.fabric.execution.context.ExecutionSource source,
    String tenantId,
    String deploymentId
) {
    static ExecutionAccessBinding from(TrustedExecutionContext context) {
        Objects.requireNonNull(context, "trusted context is required");
        ExecutionSubjectRef subject = context.subject();
        return new ExecutionAccessBinding(
            context.initiator().principalId(),
            context.initiator().principalType(),
            subject != null ? subject.subjectType() : null,
            subject != null ? subject.subjectId() : null,
            context.source(),
            context.tenantId(),
            context.deploymentId()
        );
    }

    boolean matches(TrustedExecutionContext context) {
        if (context == null) {
            return false;
        }
        return equals(from(context));
    }
}
