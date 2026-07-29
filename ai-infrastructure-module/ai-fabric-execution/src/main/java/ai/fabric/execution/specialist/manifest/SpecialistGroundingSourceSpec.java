package ai.fabric.execution.specialist.manifest;

import java.util.List;

public record SpecialistGroundingSourceSpec(
    SpecialistGroundingSourceType type,
    String name,
    int minimumCount,
    List<String> requiredEvidenceIds,
    boolean groundingUsable
) {
    public SpecialistGroundingSourceSpec {
        requiredEvidenceIds = requiredEvidenceIds == null
            ? List.of()
            : List.copyOf(requiredEvidenceIds);
    }
}
