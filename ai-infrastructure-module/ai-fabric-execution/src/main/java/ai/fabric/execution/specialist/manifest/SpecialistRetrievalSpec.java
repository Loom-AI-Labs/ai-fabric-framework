package ai.fabric.execution.specialist.manifest;

import java.util.List;

public record SpecialistRetrievalSpec(
    boolean enabled,
    List<String> vectorSpaces
) {
    public SpecialistRetrievalSpec {
        vectorSpaces = vectorSpaces == null ? List.of() : List.copyOf(vectorSpaces);
    }
}
