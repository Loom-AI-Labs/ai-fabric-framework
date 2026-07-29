package ai.fabric.execution.specialist.manifest;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.intent.orchestration.OrchestrationResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

public interface SpecialistDirectOutputProjector {

    String id();

    JsonNode project(
        OrchestrationResult result,
        List<AIEvidenceReference> evidence
    );

    static SpecialistDirectOutputProjector named(
        String id,
        BiFunction<
            OrchestrationResult,
            List<AIEvidenceReference>,
            JsonNode
        > projector
    ) {
        String normalized = SpecialistExtensionRegistrySupport.requireId(id);
        var required = Objects.requireNonNull(projector, "projector is required");
        return new SpecialistDirectOutputProjector() {
            @Override
            public String id() {
                return normalized;
            }

            @Override
            public JsonNode project(
                OrchestrationResult result,
                List<AIEvidenceReference> evidence
            ) {
                return required.apply(
                    result,
                    evidence == null ? List.of() : List.copyOf(evidence)
                );
            }
        };
    }
}
