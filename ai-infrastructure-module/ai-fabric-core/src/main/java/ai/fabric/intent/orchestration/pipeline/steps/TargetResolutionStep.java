package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.attachment.NormalizedAttachment;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.policy.SemanticRecoveryPolicy;
import ai.fabric.intent.orchestration.targets.ResolvedTarget;
import ai.fabric.intent.orchestration.targets.ResolvedTargetSource;
import ai.fabric.dto.Intent;
import ai.fabric.dto.MultiIntentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
    @Autowired(required = false)
    private ObjectProvider<InternalDecisionRecoveryService> decisionRecoveryProvider;

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

        // The model flag is repairable; actual required inputs and authority remain governed downstream.
        if (requiresTargets && (updated.getResolvedTargets() == null || updated.getResolvedTargets().isEmpty())) {
            SemanticRecoveryPolicy recoveryPolicy = semanticRecoveryPolicy(updated);
            Map<String, Object> semanticDecision = new LinkedHashMap<>(Map.of(
                "decision", "TARGET_RESOLUTION",
                "originalSource", "MODEL",
                "originalValue", "TARGET_REQUIRED",
                "validationStatus", "TARGET_NOT_RESOLVED",
                "recoveryPolicy", recoveryPolicy.missingTarget().name(),
                "recoveryAttempted", false,
                "effectiveSource", "RECOVERY_POLICY",
                "effectiveValue", "CLARIFICATION_REQUIRED"
            ));
            String clarification = MSG_CLARIFICATION_REQUIRED;
            InternalDecisionRecoveryService recovery = decisionRecoveryProvider == null ? null : decisionRecoveryProvider.getIfAvailable();
            boolean repairEnabled = recovery != null && recovery.enabledForTargetRepair(updated);
            boolean unresolved = false;
            if (repairEnabled) {
                int before = updated.getDecisionRecoveryBudget().remaining(recovery.getSettings());
                for (Intent intent : intentResponse.getIntents()) {
                    if (intent == null || !Boolean.TRUE.equals(intent.getRequiresTargetResolution())) continue;
                    var decision = recovery.repairTargetDecision(intent, updated);
                    if (decision == null) { unresolved = true; break; }
                    if (decision.requiresTargetResolution()) { clarification = decision.clarification(); break; }
                }
                semanticDecision.put("recoveryAttempted", before > updated.getDecisionRecoveryBudget().remaining(recovery.getSettings()));
                semanticDecision.put("effectiveSource", "MODEL_REPAIR");
                if (!unresolved && !anyIntentRequiresTargetResolution(intentResponse.getIntents())) {
                    semanticDecision.put("effectiveValue", "TARGET_NOT_REQUIRED");
                    return updated.withMetadata("semanticDecision", Map.copyOf(semanticDecision))
                        .withMetadata("decisionRecovery", updated.getDecisionRecoveryBudget().diagnostics(recovery.getSettings()));
                }
                if (unresolved) semanticDecision.put("effectiveValue", "REPAIR_EXHAUSTED");
            }
            PipelineContext withDecision = updated.withMetadata(
                "semanticDecision",
                Map.copyOf(semanticDecision)
            );
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("semanticDecision", Map.copyOf(semanticDecision));
            if (repairEnabled) {
                metadata.put("decisionRecovery", updated.getDecisionRecoveryBudget().diagnostics(recovery.getSettings()));
                if (unresolved) {
                    OrchestrationResult failure = InternalDecisionRecoveryService.unresolved("TARGET_DECISION_REPAIR_EXHAUSTED", Map.of())
                        .toBuilder().metadata(Map.copyOf(metadata)).build();
                    return withDecision.terminate(recovery.finalContextAnswer(withDecision, failure));
                }
            }
            return withDecision.terminate(OrchestrationResult.builder()
                .type(OrchestrationResultType.CLARIFICATION_REQUIRED)
                .success(false)
                .message(clarification)
                .data(Map.of("reason", "TARGET_REQUIRED"))
                .metadata(Map.copyOf(metadata))
                .build());
        }
        return updated;
    }

    private SemanticRecoveryPolicy semanticRecoveryPolicy(PipelineContext context) {
        OrchestrationPolicy policy = context != null
            ? context.getOrchestrationPolicy()
            : null;
        return policy != null && policy.semanticRecoveryPolicy() != null
            ? policy.semanticRecoveryPolicy()
            : SemanticRecoveryPolicy.strictDefaults();
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
