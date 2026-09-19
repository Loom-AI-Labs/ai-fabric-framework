package ai.fabric.execution.chain.manifest;

import ai.fabric.execution.chain.SpecialistChainId;
import java.util.List;
import java.util.Map;

/** Shared semantic validator for trusted offline/deployment authoring tools. */
public interface SpecialistChainManifestValidator {

    SpecialistChainManifestValidationResult validate(
        List<LoadedSpecialistChainManifest> manifests,
        Map<SpecialistChainId, String> publishedSemantics
    );
}
