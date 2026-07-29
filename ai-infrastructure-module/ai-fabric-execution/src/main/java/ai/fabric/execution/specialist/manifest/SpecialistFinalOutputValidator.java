package ai.fabric.execution.specialist.manifest;

import java.util.Objects;
import java.util.function.Consumer;

public interface SpecialistFinalOutputValidator {

    String id();

    void validate(SpecialistFinalOutputValidationContext context);

    static SpecialistFinalOutputValidator named(
        String id,
        Consumer<SpecialistFinalOutputValidationContext> validator
    ) {
        String normalized = SpecialistExtensionRegistrySupport.requireId(id);
        Consumer<SpecialistFinalOutputValidationContext> required =
            Objects.requireNonNull(validator, "validator is required");
        return new SpecialistFinalOutputValidator() {
            @Override
            public String id() {
                return normalized;
            }

            @Override
            public void validate(
                SpecialistFinalOutputValidationContext context
            ) {
                required.accept(context);
            }
        };
    }
}
