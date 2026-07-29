package ai.fabric.execution.specialist.manifest;

import java.util.List;

public record SpecialistInputSpec(
    String schemaRef,
    SpecialistInputRendering rendering,
    String primaryTextPointer,
    String conversationTextPointer,
    List<String> contextPointers,
    SpecialistInputContextSpec context
) {
    public SpecialistInputSpec {
        contextPointers = contextPointers == null
            ? List.of()
            : List.copyOf(contextPointers);
    }
}
