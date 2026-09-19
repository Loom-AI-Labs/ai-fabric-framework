package ai.fabric.execution.chain.manifest;

import ai.fabric.execution.chain.SpecialistChainId;
import ai.fabric.execution.chain.SpecialistChainLimits;
import ai.fabric.execution.specialist.manifest.SpecialistCompilationDiagnostic;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded, content-free validation result suitable for build tooling. */
public record SpecialistChainManifestValidationResult(
    boolean valid,
    List<ValidatedChain> chains,
    List<SpecialistCompilationDiagnostic> diagnostics
) {
    public SpecialistChainManifestValidationResult {
        chains = chains == null ? List.of() : List.copyOf(chains);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        valid = diagnostics.isEmpty();
    }

    public record ValidatedChain(
        SpecialistChainId id,
        Set<String> requiredSpecialists,
        Set<String> requiredSchemas,
        SpecialistChainLimits limits,
        String resourceHash,
        String declarativeSemanticsHash,
        Map<String, String> schemaDependencyHashes
    ) {
        public ValidatedChain {
            requiredSpecialists = Set.copyOf(requiredSpecialists);
            requiredSchemas = Set.copyOf(requiredSchemas);
            schemaDependencyHashes = Map.copyOf(schemaDependencyHashes);
        }
    }
}
