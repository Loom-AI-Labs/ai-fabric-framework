package ai.fabric.execution.specialist.client;

import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionResumeResult;
import ai.fabric.execution.gateway.ExecutionHandle;
import ai.fabric.execution.specialist.SpecialistId;
import java.util.Optional;

/**
 * Typed caller boundary over one immutable registered specialist.
 */
public interface SpecialistClient<I, O> {

    SpecialistId specialistId();

    AIExecutionResult<O> execute(SpecialistInvocation<I> invocation);

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
