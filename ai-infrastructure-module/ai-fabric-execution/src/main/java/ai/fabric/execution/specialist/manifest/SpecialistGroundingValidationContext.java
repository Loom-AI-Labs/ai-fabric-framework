package ai.fabric.execution.specialist.manifest;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.capability.RequestedCapabilityProfile;
import java.util.List;
import java.util.Objects;

public record SpecialistGroundingValidationContext(
    OrchestrationResult result,
    List<AIEvidenceReference> evidence,
    SpecialistGroundingSpec specification,
    RequestedCapabilityProfile capabilities
) {
    public SpecialistGroundingValidationContext {
        Objects.requireNonNull(result, "result is required");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        Objects.requireNonNull(specification, "specification is required");
        Objects.requireNonNull(capabilities, "capabilities is required");
    }
}
