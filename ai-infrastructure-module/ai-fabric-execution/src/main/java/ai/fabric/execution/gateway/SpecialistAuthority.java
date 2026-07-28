package ai.fabric.execution.gateway;

import java.util.Set;

public record SpecialistAuthority(
    Set<String> allowedActions,
    Set<String> allowedVectorSpaces
) {
    public SpecialistAuthority {
        allowedActions = allowedActions == null ? Set.of() : Set.copyOf(allowedActions);
        allowedVectorSpaces = allowedVectorSpaces == null
            ? Set.of()
            : Set.copyOf(allowedVectorSpaces);
    }
}
