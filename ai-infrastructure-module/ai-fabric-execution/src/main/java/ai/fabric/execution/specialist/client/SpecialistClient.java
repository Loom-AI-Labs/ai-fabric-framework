package ai.fabric.execution.specialist.client;

import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.AIExecutionRequest;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionResumeResult;
import ai.fabric.execution.gateway.AIInteractiveExecutionGateway;
import ai.fabric.execution.gateway.ExecutionHandle;
import ai.fabric.execution.specialist.SpecialistId;
import java.util.Optional;

/**
 * Typed caller boundary over one immutable registered specialist.
 */
public interface SpecialistClient<I, O> {

    SpecialistId specialistId();

    AIExecutionResult<O> execute(SpecialistInvocation<I> invocation);

    /**
     * Executes a typed invocation through the backend-owned interactive
     * dialogue boundary.
     */
    default AIExecutionResult<O> executeInteractive(
        SpecialistInvocation<I> invocation,
        AIInteractiveExecutionGateway interactiveGateway
    ) {
        java.util.Objects.requireNonNull(
            invocation,
            "invocation is required"
        );
        java.util.Objects.requireNonNull(
            interactiveGateway,
            "interactiveGateway is required"
        );
        return interactiveGateway.execute(
            new AIExecutionRequest<>(
                specialistId(),
                invocation.input(),
                invocation.trustedExecutionContext(),
                invocation.conversationBinding(),
                invocation.deadline(),
                invocation.idempotencyKey()
            )
        );
    }

    AIExecutionResumeResult<O> resume(
        SpecialistResumeInvocation invocation
    );

    ExecutionHandle submit(SpecialistInvocation<I> invocation);

    Optional<SpecialistExecutionSnapshot<O>> find(
        String invocationId,
        TrustedExecutionContext trustedExecutionContext
    );

    boolean cancel(
        String invocationId,
        TrustedExecutionContext trustedExecutionContext
    );

    default AIExecutionResult<O> execute(
        I input,
        TrustedExecutionContext trustedExecutionContext
    ) {
        return execute(
            SpecialistInvocation.synchronous(input, trustedExecutionContext)
        );
    }
}
