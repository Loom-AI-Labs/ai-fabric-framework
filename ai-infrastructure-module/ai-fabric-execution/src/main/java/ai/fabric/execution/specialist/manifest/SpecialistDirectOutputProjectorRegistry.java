package ai.fabric.execution.specialist.manifest;

import java.util.List;

public final class SpecialistDirectOutputProjectorRegistry {

    private final SpecialistExtensionRegistrySupport<
        SpecialistDirectOutputProjector
    > registry;

    public SpecialistDirectOutputProjectorRegistry(
        List<SpecialistDirectOutputProjector> projectors
    ) {
        this.registry = new SpecialistExtensionRegistrySupport<>(
            projectors,
            SpecialistDirectOutputProjector::id,
            "direct output projector"
        );
    }

    public SpecialistDirectOutputProjector require(String id) {
        return registry.require(id);
    }

    public List<SpecialistDirectOutputProjector> list() {
        return registry.list();
    }
}
