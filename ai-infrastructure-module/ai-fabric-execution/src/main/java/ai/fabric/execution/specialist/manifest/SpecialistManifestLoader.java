package ai.fabric.execution.specialist.manifest;

import ai.fabric.execution.config.AIExecutionProperties;

public interface SpecialistManifestLoader {

    SpecialistResourceBundle load(
        AIExecutionProperties.Manifests properties
    );
}
