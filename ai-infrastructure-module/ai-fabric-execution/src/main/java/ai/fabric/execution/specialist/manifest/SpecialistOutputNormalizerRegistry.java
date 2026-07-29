package ai.fabric.execution.specialist.manifest;

import java.util.List;

public final class SpecialistOutputNormalizerRegistry {

    private final SpecialistExtensionRegistrySupport<
        SpecialistOutputNormalizer
    > registry;

    public SpecialistOutputNormalizerRegistry(
        List<SpecialistOutputNormalizer> normalizers
    ) {
        this.registry = new SpecialistExtensionRegistrySupport<>(
            normalizers,
            SpecialistOutputNormalizer::id,
            "output normalizer"
        );
    }

    public SpecialistOutputNormalizer require(String id) {
        return registry.require(id);
    }

    public List<SpecialistOutputNormalizer> list() {
        return registry.list();
    }
}
