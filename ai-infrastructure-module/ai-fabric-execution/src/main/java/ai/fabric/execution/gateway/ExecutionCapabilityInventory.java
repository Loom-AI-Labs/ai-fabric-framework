package ai.fabric.execution.gateway;

import java.util.Set;

/**
 * Deployment-owned inventory used for capability intersection.
 */
public interface ExecutionCapabilityInventory {

    Set<String> registeredVectorSpaces();

    default Set<String> deploymentAllowedActions() {
        return Set.of();
    }
}
