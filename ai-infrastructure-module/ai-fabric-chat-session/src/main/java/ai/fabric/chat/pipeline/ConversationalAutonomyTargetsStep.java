package ai.fabric.chat.pipeline;

import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import lombok.RequiredArgsConstructor;

/** Reuse owned working-set context before the workflow planner, without an extra intent call. */
@RequiredArgsConstructor
public final class ConversationalAutonomyTargetsStep implements PipelineStep {
    private final WorkingSetTargetSeedingStep targets;
    public String getStepName() { return "ConversationalAutonomyTargets"; }
    public int getOrder() { return 48; }
    public PipelineContext process(PipelineContext context) {
        if (context == null || context.isShouldTerminate() || context.getOrchestrationContext() == null) return context;
        var workflow = context.getOrchestrationContext().getWorkflowExecution();
        if (workflow == null || !workflow.authority().continueWithinRequest()) return context;
        return targets.seedForWorkflow(context);
    }
}
