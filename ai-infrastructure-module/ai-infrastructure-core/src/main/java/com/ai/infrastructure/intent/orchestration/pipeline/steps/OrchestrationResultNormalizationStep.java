package com.ai.infrastructure.intent.orchestration.pipeline.steps;

import com.ai.infrastructure.config.OrchestrationResultNormalizationProperties;
import com.ai.infrastructure.intent.orchestration.OrchestrationResult;
import com.ai.infrastructure.intent.orchestration.OrchestrationResultDebugSnapshotStore;
import com.ai.infrastructure.intent.orchestration.OrchestrationResultNormalizer;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Pipeline step that normalizes the orchestration result into a provider-agnostic contract.
 *
 * <p><strong>Order:</strong> 65 (after intent handling, before metadata/suggestions/sanitization)</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrchestrationResultNormalizationStep implements PipelineStep {

    private static final String STEP_NAME = "OrchestrationResultNormalization";
    private static final int STEP_ORDER = 65;

    private final OrchestrationResultNormalizer normalizer;
    private final OrchestrationResultNormalizationProperties properties;

    @Override
    public String getStepName() {
        return STEP_NAME;
    }

    @Override
    public int getOrder() {
        return STEP_ORDER;
    }

    @Override
    public boolean shouldSkip(PipelineContext context) {
        return false;
    }

    @Override
    public PipelineContext process(PipelineContext context) {
        boolean enabled = properties == null || properties.isEnabled();
        boolean snapshotEnabled = properties != null && properties.isDebugSnapshotEnabled();

        OrchestrationResult raw = context.getIntentResult();
        if (raw == null) {
            return context;
        }

        if (!enabled) {
            if (snapshotEnabled) {
                OrchestrationResultDebugSnapshotStore.record(context.getRequestId(), raw);
            }
            return context;
        }

        OrchestrationResult normalized = normalizer.normalize(raw);
        if (normalized == raw) {
            if (snapshotEnabled) {
                OrchestrationResultDebugSnapshotStore.record(context.getRequestId(), raw);
            }
            return context;
        }

        log.debug("Normalized orchestration result for request {}: {} -> {}",
            context.getRequestId(),
            raw.getType(),
            normalized != null ? normalized.getType() : null);

        if (snapshotEnabled) {
            OrchestrationResultDebugSnapshotStore.record(context.getRequestId(), normalized);
        }

        return context.toBuilder()
            .intentResult(normalized)
            .build();
    }
}
