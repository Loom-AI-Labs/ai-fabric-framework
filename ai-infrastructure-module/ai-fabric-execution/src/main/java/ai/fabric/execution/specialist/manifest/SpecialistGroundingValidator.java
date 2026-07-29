package ai.fabric.execution.specialist.manifest;

import java.util.Objects;
import java.util.function.Consumer;

public interface SpecialistGroundingValidator {

    String id();

    void validate(SpecialistGroundingValidationContext context);

    static SpecialistGroundingValidator named(
        String id,
        Consumer<SpecialistGroundingValidationContext> validator
    ) {
        String normalized = SpecialistExtensionRegistrySupport.requireId(id);
        Consumer<SpecialistGroundingValidationContext> required =
            Objects.requireNonNull(validator, "validator is required");
        return new SpecialistGroundingValidator() {
            @Override
            public String id() {
                return normalized;
            }

            @Override
            public void validate(SpecialistGroundingValidationContext context) {
                required.accept(context);
            }
        };
    }
}
