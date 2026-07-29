package ai.fabric.execution.specialist.manifest;

public interface SpecialistManifestCompiler {

    SpecialistCompilationResult compile(
        SpecialistManifest manifest,
        SpecialistCompilationContext context
    );

    default SpecialistCompilationResult compile(
        LoadedSpecialistManifest loaded,
        SpecialistCompilationContext context
    ) {
        return compile(loaded.manifest(), context);
    }
}
