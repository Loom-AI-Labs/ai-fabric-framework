package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.manager.ConversationManagerComponentId;
import ai.fabric.execution.manager.ConversationManagerTargetResultProjector;

public final class BillingManagerResultProjector
    implements ConversationManagerTargetResultProjector<
        AccountDelegationCoordinatorRequest,
        BillingResolutionAssessmentResult
    > {

    public static final ConversationManagerComponentId ID =
        ConversationManagerComponentId.of(
            "billing-manager-result",
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
    public Class<BillingResolutionAssessmentResult> targetOutputType() {
        return BillingResolutionAssessmentResult.class;
    }

    @Override
    public String project(
        AccountDelegationCoordinatorRequest request,
        AIExecutionResult<BillingResolutionAssessmentResult> targetExecution
    ) {
        BillingResolutionAssessmentResult output =
            targetExecution.output();
        String explanation = output.explanation();
        if (explanation == null || explanation.isBlank()) {
            throw new IllegalArgumentException(
                "billing explanation is required"
            );
        }
        return "%s of %s follows the %s path with expected status %s. %s"
            .formatted(
                output.resolutionType(),
                output.amount().stripTrailingZeros().toPlainString(),
                output.decision(),
                output.expectedStatus(),
                explanation.trim()
            );
    }
}
