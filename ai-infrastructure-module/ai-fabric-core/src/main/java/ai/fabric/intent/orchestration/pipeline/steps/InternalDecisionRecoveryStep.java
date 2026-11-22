package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InternalDecisionRecoveryStep implements PipelineStep {
    private final InternalDecisionRecoveryService recovery;
    @Override public String getStepName() { return "InternalDecisionRecovery"; }
    @Override public int getOrder() { return 64; }
    @Override public boolean shouldSkip(PipelineContext context) { return !recovery.enabledFor(context); }
    @Override public PipelineContext process(PipelineContext context) {
        var result = recovery.finalContextAnswer(context, context.getIntentResult());
        if (result == null) return context;
        if (context.getDecisionRecoveryBudget().hasActivity()) {
            java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>(result.getMetadata());
            metadata.put("decisionRecovery", context.getDecisionRecoveryBudget().diagnostics(recovery.getSettings()));
            result = result.toBuilder().metadata(java.util.Map.copyOf(metadata)).build();
        }
        if (result == context.getIntentResult()) return context;
        return context.toBuilder().intentResult(result)
            .earlyTerminationResult(context.isShouldTerminate() ? result : context.getEarlyTerminationResult()).build();
    }
}
