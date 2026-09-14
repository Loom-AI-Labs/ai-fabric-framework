package ai.fabric.execution.chain;

import ai.fabric.execution.context.TrustedExecutionContext;
import java.util.Optional;

/** Executes and observes application-approved specialist chains. */
public interface SpecialistChainGateway {

    <I> SpecialistChainExecutionResult execute(
        SpecialistChainExecutionRequest<I> request
    );

    <I> SpecialistChainExecutionHandle submit(
        SpecialistChainExecutionRequest<I> request
    );

    Optional<SpecialistChainExecutionSnapshot> find(
        String executionId,
        TrustedExecutionContext trustedExecutionContext
    );

    Optional<SpecialistChainExecutionResult> findResult(
        String executionId,
        TrustedExecutionContext trustedExecutionContext
    );

    boolean cancel(
        String executionId,
        TrustedExecutionContext trustedExecutionContext
    );
}
