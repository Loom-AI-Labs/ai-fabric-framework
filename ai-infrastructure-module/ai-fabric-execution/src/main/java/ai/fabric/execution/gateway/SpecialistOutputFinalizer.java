package ai.fabric.execution.gateway;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import java.util.List;

/**
 * Produces a typed specialist output from bounded successful grounding.
 */
public interface SpecialistOutputFinalizer {

    <O> SpecialistOutputFinalization<O> finalizeOutput(
        SpecialistDefinition<?, O> definition,
        String applicationInput,
        OrchestrationContext orchestrationContext,
        OrchestrationResult orchestrationResult,
        List<AIEvidenceReference> evidence
    );
}
