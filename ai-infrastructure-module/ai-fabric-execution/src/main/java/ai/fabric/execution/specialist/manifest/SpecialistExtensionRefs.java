package ai.fabric.execution.specialist.manifest;

import java.util.List;

public record SpecialistExtensionRefs(
    List<String> groundingValidatorRefs,
    List<String> finalOutputValidatorRefs,
    String directProjectorRef,
    String outputNormalizerRef
) {
    public SpecialistExtensionRefs {
        groundingValidatorRefs = groundingValidatorRefs == null
            ? List.of()
            : List.copyOf(groundingValidatorRefs);
        finalOutputValidatorRefs = finalOutputValidatorRefs == null
            ? List.of()
            : List.copyOf(finalOutputValidatorRefs);
    }
}
