package ai.fabric.intent.action.invocation;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionNames;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.capability.DefaultEffectiveCapabilitiesResolver;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/**
 * Compatibility adapter used while legacy orchestration paths adopt the governed boundary.
 */
public final class GovernedActionInvocationSupport {

    private GovernedActionInvocationSupport() {
    }

    public static GovernedActionInvocation invocation(
        String actionName,
        Map<String, Object> parameters,
        ActionContext actionContext,
        AIActionRegistry registry,
        ActionConfirmationState confirmationState,
        List<AIEvidenceReference> trustedEvidence
    ) {
        PipelineContext pipelineContext = actionContext != null
            ? actionContext.pipelineContext()
            : null;
        return new GovernedActionInvocation(
            actionName,
            parameters,
            actionContext,
            trustedContext(pipelineContext),
            effectiveProfileForAction(
                registry,
                pipelineContext,
                actionContext.orchestrationContext(),
                actionName
            ),
            confirmationState,
            trustedEvidence
        );
    }

    private static EffectiveCapabilityProfile effectiveProfileForAction(
        AIActionRegistry registry,
        PipelineContext pipelineContext,
        OrchestrationContext orchestrationContext,
        String actionName
    ) {
        if (pipelineContext != null && pipelineContext.getEffectiveCapabilityProfile() != null) {
            return pipelineContext.getEffectiveCapabilityProfile();
        }
        List<AIActionMetaData> metadata = new ArrayList<>();
        if (registry.getAllMetadata() != null) {
            metadata.addAll(registry.getAllMetadata());
        }
        boolean present = metadata.stream()
            .filter(java.util.Objects::nonNull)
            .filter(value -> value.getName() != null)
            .anyMatch(value -> AIActionNames.normalize(value.getName())
                .equals(AIActionNames.normalize(actionName)));
        if (!present) {
            registry.findMetadata(actionName)
                .or(() -> registry.findHandler(actionName)
                    .map(handler -> handler.getActionMetadata()))
                .ifPresent(metadata::add);
        }
        OrchestrationPolicy policy = pipelineContext != null
            ? pipelineContext.getOrchestrationPolicy()
            : null;
        if (policy == null && orchestrationContext != null) {
            policy = orchestrationContext.getOrchestrationPolicy();
        }
        return new DefaultEffectiveCapabilitiesResolver().resolveLegacy(policy, metadata);
    }

    public static EffectiveCapabilityProfile effectiveProfile(
        AIActionRegistry registry,
        PipelineContext pipelineContext,
        OrchestrationContext orchestrationContext
    ) {
        if (pipelineContext != null && pipelineContext.getEffectiveCapabilityProfile() != null) {
            return pipelineContext.getEffectiveCapabilityProfile();
        }
        OrchestrationPolicy policy = pipelineContext != null
            ? pipelineContext.getOrchestrationPolicy()
            : null;
        if (policy == null && orchestrationContext != null) {
            policy = orchestrationContext.getOrchestrationPolicy();
        }
        return new DefaultEffectiveCapabilitiesResolver()
            .resolveLegacy(policy, registry.getAllMetadata());
    }

    public static TrustedExecutionContext trustedContext(PipelineContext pipelineContext) {
        if (pipelineContext == null || pipelineContext.getOrchestrationRequest() == null) {
            return null;
        }
        return pipelineContext.getOrchestrationRequest().trustedExecutionContext();
    }
}
