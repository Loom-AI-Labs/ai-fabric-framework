package ai.fabric.execution.gateway;

import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.specialist.SpecialistDefinition;

public interface SpecialistAuthorityResolver {

    SpecialistAuthority resolve(
        SpecialistDefinition<?, ?> definition,
        TrustedExecutionContext trustedContext
    );
}
