package ai.fabric.execution.chain.manifest;

import ai.fabric.execution.chain.SpecialistChainRegistration;

/** Compiles one strict chain resource into the existing runtime contract. */
public interface SpecialistChainManifestCompiler {

    SpecialistChainRegistration compile(
        LoadedSpecialistChainManifest manifest,
        SpecialistChainCompilationContext context
    );
}
