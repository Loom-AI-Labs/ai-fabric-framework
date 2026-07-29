package ai.fabric.execution.specialist.manifest;

import java.util.List;

public record SpecialistGroundingSpec(
    SpecialistGroundingRequirement requirement,
    boolean requireEvidenceCitations,
    List<SpecialistGroundingSourceSpec> sources,
    List<String> validatorRefs
) {
    public SpecialistGroundingSpec {
        sources = sources == null ? List.of() : List.copyOf(sources);
        validatorRefs = validatorRefs == null
            ? List.of()
            : List.copyOf(validatorRefs);
    }
}
