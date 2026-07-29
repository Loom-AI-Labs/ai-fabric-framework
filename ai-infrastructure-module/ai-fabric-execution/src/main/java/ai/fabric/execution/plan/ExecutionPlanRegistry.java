package ai.fabric.execution.plan;

import java.util.List;
import java.util.Optional;

public interface ExecutionPlanRegistry {

    Optional<RegisteredExecutionPlan> find(ExecutionPlanId id);

    List<RegisteredExecutionPlan> list();

    default RegisteredExecutionPlan require(ExecutionPlanId id) {
        return find(id).orElseThrow(() ->
            new PlanNotFoundException("No execution plan is registered for " + id)
        );
    }

    final class PlanNotFoundException extends RuntimeException {
        public PlanNotFoundException(String message) {
            super(message);
        }
    }
}
