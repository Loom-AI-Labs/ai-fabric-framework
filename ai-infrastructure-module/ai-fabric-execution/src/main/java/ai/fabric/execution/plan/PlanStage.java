package ai.fabric.execution.plan;

/**
 * One immutable stage in a registered fixed execution plan.
 */
public sealed interface PlanStage
    permits SpecialistPlanStep, ParallelPlanStep {

    String id();
}
