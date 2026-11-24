package ai.fabric.execution.gateway;

import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.workflow.WorkflowExecution;

/** Host authority/checkpoint wiring, not an application planner or model-generated grant. */
@FunctionalInterface
public interface WorkflowExecutionBindingProvider {
    WorkflowExecution bind(String invocationId, SpecialistDefinition<?, ?> definition,
                           AIExecutionRequest<?> request, OrchestrationContext context);
}
