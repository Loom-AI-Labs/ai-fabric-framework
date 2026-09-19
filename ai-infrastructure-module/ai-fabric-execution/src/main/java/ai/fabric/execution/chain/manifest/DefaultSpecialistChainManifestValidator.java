package ai.fabric.execution.chain.manifest;

import ai.fabric.execution.chain.SpecialistChainId;
import ai.fabric.execution.chain.SpecialistChainRegistration;
import ai.fabric.execution.specialist.manifest.SpecialistCompilationDiagnostic;
import ai.fabric.execution.specialist.manifest.SpecialistManifestException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class DefaultSpecialistChainManifestValidator
    implements SpecialistChainManifestValidator {

    private final SpecialistChainManifestCompiler compiler;
    private final SpecialistChainCompilationContext context;

    public DefaultSpecialistChainManifestValidator(
        SpecialistChainManifestCompiler compiler,
        SpecialistChainCompilationContext context
    ) {
        this.compiler = Objects.requireNonNull(compiler, "compiler is required");
        this.context = Objects.requireNonNull(context, "context is required");
    }

    @Override
    public SpecialistChainManifestValidationResult validate(
        List<LoadedSpecialistChainManifest> manifests,
        Map<SpecialistChainId, String> publishedSemantics
    ) {
        List<SpecialistChainManifestValidationResult.ValidatedChain> valid =
            new ArrayList<>();
        List<SpecialistCompilationDiagnostic> diagnostics = new ArrayList<>();
        Set<SpecialistChainId> ids = new LinkedHashSet<>();
        Map<SpecialistChainId, String> baseline = publishedSemantics == null
            ? Map.of()
            : Map.copyOf(publishedSemantics);
        for (LoadedSpecialistChainManifest loaded
            : manifests == null ? List.<LoadedSpecialistChainManifest>of()
                : manifests) {
            try {
                SpecialistChainRegistration registration = compiler.compile(
                    loaded,
                    context
                );
                SpecialistChainId id = registration.definition().id();
                if (!ids.add(id)) {
                    throw new SpecialistManifestException(
                        "CHAIN_MANIFEST_DEFINITION_DUPLICATE",
                        "A chain ID occurs more than once in the candidate bundle.",
                        loaded.source()
                    );
                }
                String semantics = registration.identity()
                    .declarativeSemanticsHash().orElseThrow();
                String prior = baseline.get(id);
                if (prior != null && !prior.equals(semantics)) {
                    throw new SpecialistManifestException(
                        "CHAIN_MANIFEST_EXACT_VERSION_REUSED",
                        "Published chain semantics changed under an exact ID.",
                        loaded.source()
                    );
                }
                Set<String> specialists = new LinkedHashSet<>();
                specialists.add(
                    registration.definition().managerSpecialistId().toString()
                );
                registration.definition().targets().forEach(target ->
                    specialists.add(target.specialistId().toString())
                );
                Set<String> schemas = registration.identity()
                    .schemaDependencies().keySet().stream()
                    .map(Object::toString)
                    .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new
                    ));
                valid.add(new SpecialistChainManifestValidationResult
                    .ValidatedChain(
                        id,
                        specialists,
                        schemas,
                        registration.definition().limits(),
                        registration.identity().resourceHash().orElseThrow(),
                        semantics,
                        registration.identity().schemaDependencies().entrySet()
                            .stream()
                            .collect(java.util.stream.Collectors.toMap(
                                entry -> entry.getKey().toString(),
                                Map.Entry::getValue
                            ))
                    ));
            } catch (SpecialistManifestException ex) {
                diagnostics.add(new SpecialistCompilationDiagnostic(
                    ex.reason(),
                    ex.getMessage(),
                    ex.source() == null ? loaded.source() : ex.source()
                ));
            }
        }
        return new SpecialistChainManifestValidationResult(
            diagnostics.isEmpty(),
            valid,
            diagnostics
        );
    }
}
