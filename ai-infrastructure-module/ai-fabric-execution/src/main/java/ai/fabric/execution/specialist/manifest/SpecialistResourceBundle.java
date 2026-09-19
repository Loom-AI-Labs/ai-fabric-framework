package ai.fabric.execution.specialist.manifest;

import ai.fabric.execution.chain.manifest.LoadedSpecialistChainManifest;
import java.util.List;

public record SpecialistResourceBundle(
    List<LoadedSpecialistManifest> manifests,
    List<SpecialistSchemaDefinition> schemas,
    List<SpecialistPromptProfile> promptProfiles,
    List<LoadedSpecialistChainManifest> chainManifests,
    List<SpecialistCompilationDiagnostic> diagnostics
) {
    public SpecialistResourceBundle {
        manifests = manifests == null ? List.of() : List.copyOf(manifests);
        schemas = schemas == null ? List.of() : List.copyOf(schemas);
        promptProfiles = promptProfiles == null
            ? List.of()
            : List.copyOf(promptProfiles);
        chainManifests = chainManifests == null
            ? List.of()
            : List.copyOf(chainManifests);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public SpecialistResourceBundle(
        List<LoadedSpecialistManifest> manifests,
        List<SpecialistSchemaDefinition> schemas,
        List<SpecialistPromptProfile> promptProfiles,
        List<SpecialistCompilationDiagnostic> diagnostics
    ) {
        this(manifests, schemas, promptProfiles, List.of(), diagnostics);
    }

    public SpecialistResourceBundle(
        List<LoadedSpecialistManifest> manifests,
        List<SpecialistSchemaDefinition> schemas,
        List<SpecialistPromptProfile> promptProfiles
    ) {
        this(manifests, schemas, promptProfiles, List.of(), List.of());
    }

    public static SpecialistResourceBundle empty() {
        return new SpecialistResourceBundle(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }
}
