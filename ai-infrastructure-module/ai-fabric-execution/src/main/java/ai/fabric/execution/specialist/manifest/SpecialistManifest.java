package ai.fabric.execution.specialist.manifest;

public record SpecialistManifest(
    String apiVersion,
    String kind,
    SpecialistManifestMetadata metadata,
    SpecialistManifestSpec spec
) {}
