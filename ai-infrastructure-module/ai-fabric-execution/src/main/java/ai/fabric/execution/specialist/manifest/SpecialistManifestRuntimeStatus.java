package ai.fabric.execution.specialist.manifest;

import java.util.List;
import java.util.Objects;

public record SpecialistManifestRuntimeStatus(
    boolean enabled,
    boolean ready,
    int loadedDefinitionCount,
    int manifestDefinitionCount,
    int javaDefinitionCount,
    String registryContentHash,
    List<SpecialistCompilationDiagnostic> diagnostics
) {
    public SpecialistManifestRuntimeStatus {
        if (loadedDefinitionCount < 0
            || manifestDefinitionCount < 0
            || javaDefinitionCount < 0) {
            throw new IllegalArgumentException(
                "Definition counts must not be negative"
            );
        }
        registryContentHash = Objects.requireNonNull(
            registryContentHash,
            "registryContentHash is required"
        );
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
