package ai.fabric.execution.gateway;

import ai.fabric.execution.specialist.SpecialistDefinition;
import java.time.Instant;

/**
 * Internal bridge that preserves one framework invocation ID during durable
 * dispatch and recovery.
 */
interface AssignedExecutionRunner {

    AIExecutionResult<?> executeAssigned(
        String invocationId,
        AIExecutionRequest<?> request
    );

    Instant resolveDeadline(
        AIExecutionRequest<?> request,
        SpecialistDefinition<?, ?> definition
    );
}
