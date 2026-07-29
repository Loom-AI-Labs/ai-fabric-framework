package ai.fabric.execution.specialist.manifest;

public record SpecialistSchemaDefinition(
    String apiVersion,
    String kind,
    SpecialistResourceMetadata metadata,
    SpecialistSchemaSpec spec
) {
    public SpecialistSchemaId id() {
        return new SpecialistSchemaId(metadata.name(), metadata.version());
    }
}
