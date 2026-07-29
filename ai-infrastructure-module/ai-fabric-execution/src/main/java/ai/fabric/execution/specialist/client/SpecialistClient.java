package ai.fabric.execution.specialist.client;

import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.specialist.SpecialistId;

/**
 * Typed caller convenience over one immutable manifest-backed specialist.
 */
public interface SpecialistClient<I, O> {

    SpecialistId specialistId();

    AIExecutionResult<O> execute(SpecialistInvocation<I> invocation);

    default AIExecutionResult<O> execute(
        I input,
        TrustedExecutionContext trustedExecutionContext
    ) {
        return execute(
            SpecialistInvocation.synchronous(input, trustedExecutionContext)
        );
    }
}
