package ai.fabric.execution.specialist.manifest;

import ai.fabric.execution.specialist.SpecialistOutputMode;
import java.util.List;

public record SpecialistOutputSpec(
    SpecialistOutputMode mode,
    String schemaRef,
    String directProjectorRef,
    String conversationTextPointer,
    List<String> finalValidatorRefs,
    String normalizerRef
) {
    public SpecialistOutputSpec {
        finalValidatorRefs = finalValidatorRefs == null
            ? List.of()
            : List.copyOf(finalValidatorRefs);
    }
}
