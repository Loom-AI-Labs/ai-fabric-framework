package ai.fabric.execution.specialist.manifest;

public record SpecialistPromptProfile(
    String apiVersion,
    String kind,
    SpecialistResourceMetadata metadata,
    SpecialistPromptProfileSpec spec
) {
    public SpecialistPromptProfileId id() {
        return new SpecialistPromptProfileId(
            metadata.name(),
            metadata.version()
        );
    }
}
