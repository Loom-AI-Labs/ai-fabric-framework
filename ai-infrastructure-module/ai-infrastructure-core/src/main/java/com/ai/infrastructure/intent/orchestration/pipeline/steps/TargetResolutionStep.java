package com.ai.infrastructure.intent.orchestration.pipeline.steps;

import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.attachment.NormalizedAttachment;
import com.ai.infrastructure.intent.orchestration.OrchestrationResult;
import com.ai.infrastructure.intent.orchestration.OrchestrationResultType;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineStep;
import com.ai.infrastructure.intent.orchestration.targets.ResolvedTarget;
import com.ai.infrastructure.intent.orchestration.targets.ResolvedTargetSource;
import com.ai.infrastructure.dto.Intent;
import com.ai.infrastructure.dto.MultiIntentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves request targets deterministically (active attachments first; working set later).
 *
 * <p><strong>Order:</strong> 52 (after intent extraction; before vectorSpace resolution)</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TargetResolutionStep implements PipelineStep {

    private static final String STEP_NAME = "TargetResolution";
    private static final int STEP_ORDER = 52;

    private static final String METADATA_KEY_TARGET_RESOLUTION = "targetResolution";
    private static final String MSG_CLARIFICATION_REQUIRED = "Which item are you referring to? Select an attachment or specify an explicit identifier.";

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
        if (context == null || context.isShouldTerminate()) {
            return context;
        }

        OrchestrationContext orchContext = context.getOrchestrationContext();
        if (orchContext == null) {
            return context;
        }

        MultiIntentResponse intentResponse = context.getIntentResponse();
        boolean requiresTargets = intentResponse != null
            && intentResponse.getIntents() != null
            && !intentResponse.getIntents().isEmpty()
            && anyIntentRequiresTargetResolution(intentResponse.getIntents());

        List<NormalizedAttachment> attachments = orchContext.getAttachmentsNormalized();

        PipelineContext updated = context;

        // Always pin request attachments when they exist, regardless of LLM intent flags.
        List<ResolvedTarget> resolved = resolveRequestAttachments(attachments);
        if (!resolved.isEmpty()) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("source", ResolvedTargetSource.REQUEST_ATTACHMENTS.name());
            meta.put("count", resolved.size());

            updated = context.toBuilder()
                .resolvedTargets(Collections.unmodifiableList(resolved))
                .build()
                .withMetadata(METADATA_KEY_TARGET_RESOLUTION, Collections.unmodifiableMap(meta));
        } else if (updated.getResolvedTargets() != null && !updated.getResolvedTargets().isEmpty()) {
            // Targets were already present (e.g., seeded from conversation metadata); attach diagnostics.
            ResolvedTargetSource source = resolveUniformSource(updated.getResolvedTargets());
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("source", source != null ? source.name() : "MIXED");
            meta.put("count", updated.getResolvedTargets().size());
            updated = updated.withMetadata(METADATA_KEY_TARGET_RESOLUTION, Collections.unmodifiableMap(meta));
        }

        // Fail-closed only when target resolution is explicitly required.
        if (requiresTargets && (updated.getResolvedTargets() == null || updated.getResolvedTargets().isEmpty())) {
            return updated.terminate(OrchestrationResult.builder()
                .type(OrchestrationResultType.CLARIFICATION_REQUIRED)
                .success(false)
                .message(MSG_CLARIFICATION_REQUIRED)
                .data(Map.of("reason", "TARGET_REQUIRED"))
                .build());
        }
        return updated;
    }

    private boolean anyIntentRequiresTargetResolution(List<Intent> intents) {
        if (intents == null || intents.isEmpty()) {
            return false;
        }
        for (Intent intent : intents) {
            if (intent != null && Boolean.TRUE.equals(intent.getRequiresTargetResolution())) {
                return true;
            }
        }
        return false;
    }

    private List<ResolvedTarget> resolveRequestAttachments(List<NormalizedAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }

        List<ResolvedTarget> resolved = new ArrayList<>();
        for (NormalizedAttachment attachment : attachments) {
            if (attachment == null) {
                continue;
            }
            resolved.add(ResolvedTarget.builder()
                .id(StringUtils.hasText(attachment.getId()) ? attachment.getId() : null)
                .vectorSpace(attachment.getVectorSpace())
                .contentText(attachment.getContentText())
                .contentTextTruncated(attachment.isContentTextTruncated())
                .metadata(attachment.getMetadata() != null ? attachment.getMetadata() : Map.of())
                .source(ResolvedTargetSource.REQUEST_ATTACHMENTS)
                .build());
        }

        return resolved;
    }

    private ResolvedTargetSource resolveUniformSource(List<ResolvedTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return null;
        }
        ResolvedTargetSource source = null;
        for (ResolvedTarget target : targets) {
            if (target == null || target.getSource() == null) {
                return null;
            }
            if (source == null) {
                source = target.getSource();
                continue;
            }
            if (source != target.getSource()) {
                return null;
            }
        }
        return source;
    }
}
