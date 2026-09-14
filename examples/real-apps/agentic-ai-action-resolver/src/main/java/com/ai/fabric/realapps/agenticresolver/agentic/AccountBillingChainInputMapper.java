package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.chain.SpecialistChainComponentId;
import ai.fabric.execution.chain.SpecialistChainTargetInputMapper;
import ai.fabric.execution.chain.SpecialistChainTargetRequest;

public final class AccountBillingChainInputMapper
    implements SpecialistChainTargetInputMapper<
        AccountDelegationCoordinatorRequest,
        BillingResolutionAssessmentRequest
    > {

    static final String SCOPED_QUESTION =
        "Assess only the supplied billing resolution against approved policy. "
            + "Do not inspect account readiness.";

    public static final SpecialistChainComponentId ID =
        SpecialistChainComponentId.of("account-chain-billing-input", "1");

    @Override
    public SpecialistChainComponentId id() {
        return ID;
    }

    @Override
    public Class<AccountDelegationCoordinatorRequest> chainRequestType() {
        return AccountDelegationCoordinatorRequest.class;
    }

    @Override
    public Class<BillingResolutionAssessmentRequest> targetInputType() {
        return BillingResolutionAssessmentRequest.class;
    }

    @Override
    public BillingResolutionAssessmentRequest map(
        AccountDelegationCoordinatorRequest request,
        SpecialistChainTargetRequest targetRequest
    ) {
        if (request.resolutionType() == null || request.amount() == null) {
            throw new IllegalArgumentException(
                "Complete billing facts are required"
            );
        }
        return new BillingResolutionAssessmentRequest(
            SCOPED_QUESTION,
            request.resolutionType(),
            request.amount()
        );
    }
}
