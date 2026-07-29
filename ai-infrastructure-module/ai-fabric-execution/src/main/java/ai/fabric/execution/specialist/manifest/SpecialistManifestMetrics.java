package ai.fabric.execution.specialist.manifest;

import ai.fabric.execution.specialist.SpecialistDefinitionSource;

public interface SpecialistManifestMetrics {

    void recordLoad(String result, String reason);

    void recordValidation(String result, String reason);

    void recordRegistryCounts(int javaDefinitions, int manifestDefinitions);

    void recordExecution(
        SpecialistDefinitionSource source,
        String result
    );

    static SpecialistManifestMetrics noop() {
        return new SpecialistManifestMetrics() {
            @Override
            public void recordLoad(String result, String reason) {}

            @Override
            public void recordValidation(String result, String reason) {}

            @Override
            public void recordRegistryCounts(
                int javaDefinitions,
                int manifestDefinitions
            ) {}

            @Override
            public void recordExecution(
                SpecialistDefinitionSource source,
                String result
            ) {}
        };
    }
}
