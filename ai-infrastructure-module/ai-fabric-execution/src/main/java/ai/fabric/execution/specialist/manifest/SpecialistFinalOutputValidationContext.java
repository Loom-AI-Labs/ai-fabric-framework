package ai.fabric.execution.specialist.manifest;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.intent.orchestration.OrchestrationResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

public record SpecialistFinalOutputValidationContext(
    JsonNode output,
    OrchestrationResult sourceResult,
    List<AIEvidenceReference> evidence
) {
    public SpecialistFinalOutputValidationContext {
        Objects.requireNonNull(output, "output is required");
        Objects.requireNonNull(sourceResult, "sourceResult is required");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
