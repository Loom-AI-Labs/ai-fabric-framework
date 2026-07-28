package ai.fabric.execution.gateway;

import java.util.LinkedHashSet;
import java.util.Set;

public final class StaticExecutionCapabilityInventory
    implements ExecutionCapabilityInventory {

    private final Set<String> registeredVectorSpaces;
    private final Set<String> deploymentAllowedActions;

    public StaticExecutionCapabilityInventory(
        Set<String> registeredVectorSpaces,
        Set<String> deploymentAllowedActions
    ) {
        this.registeredVectorSpaces = immutable(registeredVectorSpaces);
        this.deploymentAllowedActions = immutable(deploymentAllowedActions);
    }

    @Override
    public Set<String> registeredVectorSpaces() {
        return registeredVectorSpaces;
    }

    @Override
    public Set<String> deploymentAllowedActions() {
        return deploymentAllowedActions;
    }

    private Set<String> immutable(Set<String> values) {
        return values == null || values.isEmpty()
            ? Set.of()
            : Set.copyOf(new LinkedHashSet<>(values));
    }
}
