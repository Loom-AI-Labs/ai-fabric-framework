package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.chain.SpecialistChainComponentId;
import ai.fabric.execution.chain.SpecialistChainResultProjection;
import ai.fabric.execution.chain.SpecialistChainTargetResultProjector;
import ai.fabric.execution.gateway.AIExecutionResult;
import java.util.LinkedHashMap;

public final class AccountBillingChainResultProjector
    implements SpecialistChainTargetResultProjector<
        AccountDelegationCoordinatorRequest,
        BillingResolutionAssessmentResult
    > {

    public static final SpecialistChainComponentId ID =
        SpecialistChainComponentId.of("account-chain-billing-result", "1");

    @Override
    public SpecialistChainComponentId id() {
        return ID;
    }

    @Override
    public Class<AccountDelegationCoordinatorRequest> chainRequestType() {
        return AccountDelegationCoordinatorRequest.class;
    }

    @Override
    public Class<BillingResolutionAssessmentResult> targetOutputType() {
        return BillingResolutionAssessmentResult.class;
    }

    @Override
    public SpecialistChainResultProjection project(
        AccountDelegationCoordinatorRequest request,
        AIExecutionResult<BillingResolutionAssessmentResult> execution
    ) {
        BillingResolutionAssessmentResult output = execution.output();
        LinkedHashMap<String, String> facts = new LinkedHashMap<>();
        facts.put("resolutionType", output.resolutionType().name());
        facts.put(
            "amount",
            output.amount().stripTrailingZeros().toPlainString()
        );
        facts.put("decision", output.decision().name());
        facts.put("expectedStatus", output.expectedStatus().name());
        facts.put(
            "automaticLimit",
            output.automaticLimit().stripTrailingZeros().toPlainString()
        );
        return new SpecialistChainResultProjection(
            output.explanation(),
            facts,
            execution.evidence().stream()
                .map(AIEvidenceReference::evidenceId)
                .distinct()
                .toList()
        );
    }
}
