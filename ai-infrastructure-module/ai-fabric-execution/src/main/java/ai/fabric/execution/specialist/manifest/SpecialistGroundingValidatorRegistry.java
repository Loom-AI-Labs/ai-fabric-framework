package ai.fabric.execution.specialist.manifest;

import java.util.List;

public final class SpecialistGroundingValidatorRegistry {

    private final SpecialistExtensionRegistrySupport<
        SpecialistGroundingValidator
    > registry;

    public SpecialistGroundingValidatorRegistry(
        List<SpecialistGroundingValidator> validators
    ) {
        this.registry = new SpecialistExtensionRegistrySupport<>(
            validators,
            SpecialistGroundingValidator::id,
            "grounding validator"
        );
    }

    public SpecialistGroundingValidator require(String id) {
        return registry.require(id);
    }

    public List<SpecialistGroundingValidator> list() {
        return registry.list();
    }
}
