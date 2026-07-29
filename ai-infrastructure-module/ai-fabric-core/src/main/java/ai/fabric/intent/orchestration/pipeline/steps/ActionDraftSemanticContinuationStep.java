package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves ambiguous action-draft follow-ups before general intent extraction.
 */
@Component
@RequiredArgsConstructor
public class ActionDraftSemanticContinuationStep implements PipelineStep {

    private static final String STEP_NAME =
        "ActionDraftSemanticContinuation";
    private static final int STEP_ORDER = 29;
    private static final String METADATA_KEY =
        "actionDraftSemanticContinuation";

    private final ActionDraftContinuationAnalyzer analyzer;

    @Override
    public String getStepName() {
        return STEP_NAME;
    }

    @Override
    public int getOrder() {
        return STEP_ORDER;
    }

    @Override
    public PipelineContext process(PipelineContext context) {
        if (context == null
            || context.isShouldTerminate()
            || context.getActionDraftContinuation() == null
            || context.getActionDraftIntentResponse() != null) {
            return context;
        }
        ActionDraftContinuationAnalyzer.AnalysisOutcome outcome =
            analyzer.analyze(context);
        PipelineContext updated = context.withMetadata(
            METADATA_KEY,
            outcome.diagnostics()
        );
        if (!outcome.continued() || outcome.response() == null) {
            return updated;
        }
        return updated.toBuilder()
            .actionDraftIntentResponse(outcome.response())
            .build();
    }
}
