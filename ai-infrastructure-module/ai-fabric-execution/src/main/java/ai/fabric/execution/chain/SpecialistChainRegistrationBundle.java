package ai.fabric.execution.chain;

import ai.fabric.execution.specialist.manifest.SpecialistCompilationDiagnostic;
import java.util.List;

/** Compiled registrations and bounded diagnostics for registry publication. */
public record SpecialistChainRegistrationBundle(
    List<SpecialistChainRegistration> registrations,
    List<SpecialistCompilationDiagnostic> diagnostics,
    int discoveredManifestCount,
    int compiledManifestCount
) {
    public SpecialistChainRegistrationBundle {
        registrations = registrations == null
            ? List.of()
            : List.copyOf(registrations);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        if (discoveredManifestCount < 0 || compiledManifestCount < 0) {
            throw new IllegalArgumentException(
                "Manifest counts must not be negative"
            );
        }
        if (compiledManifestCount > discoveredManifestCount) {
            throw new IllegalArgumentException(
                "Compiled manifest count cannot exceed discovered count"
            );
        }
    }
}
