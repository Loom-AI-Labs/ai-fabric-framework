package ai.fabric.execution.specialist.manifest;

import java.util.List;

public final class SpecialistFinalOutputValidatorRegistry {

    private final SpecialistExtensionRegistrySupport<
        SpecialistFinalOutputValidator
    > registry;

    public SpecialistFinalOutputValidatorRegistry(
        List<SpecialistFinalOutputValidator> validators
    ) {
        this.registry = new SpecialistExtensionRegistrySupport<>(
            validators,
            SpecialistFinalOutputValidator::id,
            "final output validator"
        );
    }

    public SpecialistFinalOutputValidator require(String id) {
        return registry.require(id);
    }

    public List<SpecialistFinalOutputValidator> list() {
        return registry.list();
    }
}
