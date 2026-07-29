package com.ai.fabric.realapps.agenticresolver.agentic.review;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.action.ActionProposalCoordinator;
import ai.fabric.execution.action.ActionProposalView;
import ai.fabric.execution.gateway.SpecialistCapabilityResolver;
import ai.fabric.execution.review.continuation.ReviewCorrectionContext;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.invocation.ActionProposalCandidate;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.steps.OrchestrationPolicyResolutionStep;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import ai.fabric.intent.orchestration.request.OrchestrationRequestPurpose;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolverSpecialists;
import com.ai.fabric.realapps.agenticresolver.entity.RefundRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
    prefix = "ai.execution.reviews",
    name = "enabled",
    havingValue = "true"
)
public class SupportCreditCorrectionProposalService {

    private final SpecialistRegistry specialists;
    private final OrchestrationPolicyResolutionStep policyResolution;
    private final SpecialistCapabilityResolver capabilities;
    private final ActionProposalCoordinator proposals;

    public SupportCreditCorrectionProposalService(
        SpecialistRegistry specialists,
        OrchestrationPolicyResolutionStep policyResolution,
        SpecialistCapabilityResolver capabilities,
        ActionProposalCoordinator proposals
    ) {
        this.specialists = specialists;
        this.policyResolution = policyResolution;
        this.capabilities = capabilities;
        this.proposals = proposals;
    }

    public ActionProposalView propose(ReviewCorrectionContext context) {
        JsonNode correction = context.correction();
        BigDecimal amount = correction.get("amount").decimalValue();
        String reason = correction.get("reason").asText();
        RefundRequest.ResolutionType resolutionType =
            RefundRequest.ResolutionType.valueOf(
                correction.get("resolutionType").asText()
            );
        SpecialistDefinition<?, ?> specialist = specialists.require(
            AccountResolverSpecialists.SUPPORT_CREDIT_SPECIALIST_ID
        );
        OrchestrationContext orchestrationContext =
            OrchestrationContext.builder()
                .userId(
                    context.sourceContext().subject().subjectId()
                )
                .position(specialist.executionProfile().mode())
                .mode(specialist.executionProfile().mode())
                .build();
        OrchestrationRequest request = new OrchestrationRequest(
            "Validate a typed reviewer correction.",
            orchestrationContext,
            context.sourceContext(),
            ConversationPersistencePolicy.NEVER,
            null,
            null,
            null,
            OrchestrationRequestPurpose.SPECIALIST
        );
        PipelineContext preflight = policyResolution.process(
            PipelineContext.from(request)
        );
        EffectiveCapabilityProfile profile = capabilities.resolve(
            specialist,
            preflight,
            context.sourceContext()
        );
        Map<String, Object> parameters = Map.of(
            "amount",
            amount,
            "reason",
            reason,
            "resolutionType",
            resolutionType.name()
        );
        return proposals.propose(
            "review-correction-" + context.task().taskId(),
            specialist,
            new ActionProposalCandidate(
                AccountResolverSpecialists.REQUEST_REFUND_ACTION,
                parameters,
                new ActionContext(
                    orchestrationContext,
                    preflight,
                    parameters
                )
            ),
            context.sourceContext(),
            profile,
            "review-correction-" + context.task().taskId(),
            List.of(refundPolicyEvidence())
        );
    }

    private AIEvidenceReference refundPolicyEvidence() {
        return new AIEvidenceReference(
            "REFUND_OR_CREDIT_AVAILABLE",
            "Small refunds and account credits may be resolved through the governed billing-resolution action; policy and reviewer authority still apply.",
            1.0,
            "policy",
            null,
            AccountResolverSpecialists.POLICY_VECTOR_SPACE,
            Map.of("policyCode", "REFUND_OR_CREDIT_AVAILABLE")
        );
    }
}
