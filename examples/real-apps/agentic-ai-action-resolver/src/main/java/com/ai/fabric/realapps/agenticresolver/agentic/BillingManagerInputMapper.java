package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.manager.ConversationManagerComponentId;
import ai.fabric.execution.manager.ConversationManagerTargetInputMapper;

public final class BillingManagerInputMapper
    implements ConversationManagerTargetInputMapper<
        AccountDelegationCoordinatorRequest,
        BillingResolutionAssessmentRequest
    > {

    public static final ConversationManagerComponentId ID =
        ConversationManagerComponentId.of(
            "billing-manager-input",
            "1"
        );

    @Override
    public ConversationManagerComponentId id() {
        return ID;
    }

    @Override
    public Class<AccountDelegationCoordinatorRequest> managerRequestType() {
        return AccountDelegationCoordinatorRequest.class;
    }

    @Override
    public Class<BillingResolutionAssessmentRequest> targetInputType() {
        return BillingResolutionAssessmentRequest.class;
    }

    @Override
    public BillingResolutionAssessmentRequest map(
        AccountDelegationCoordinatorRequest request
    ) {
        if (request.resolutionType() == null || request.amount() == null) {
            throw new IllegalArgumentException(
                "Complete billing facts are required"
            );
        }
        return new BillingResolutionAssessmentRequest(
            request.question(),
            request.resolutionType(),
            request.amount()
        );
    }
}
