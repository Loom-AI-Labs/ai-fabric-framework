package ai.fabric.execution.specialist.manifest;

import java.util.List;

public record SpecialistInputSpec(
    String schemaRef,
    String continuationRef,
    SpecialistInputRendering rendering,
    String primaryTextPointer,
    String conversationTextPointer,
    List<String> contextPointers,
    SpecialistInputContextSpec context
) {
    public SpecialistInputSpec(
        String schemaRef,
        SpecialistInputRendering rendering,
        String primaryTextPointer,
        String conversationTextPointer,
        List<String> contextPointers,
        SpecialistInputContextSpec context
    ) {
        this(
            schemaRef,
            null,
            rendering,
            primaryTextPointer,
            conversationTextPointer,
            contextPointers,
            context
        );
    }

    public SpecialistInputSpec {
        contextPointers = contextPointers == null
            ? List.of()
            : List.copyOf(contextPointers);
    }
}
