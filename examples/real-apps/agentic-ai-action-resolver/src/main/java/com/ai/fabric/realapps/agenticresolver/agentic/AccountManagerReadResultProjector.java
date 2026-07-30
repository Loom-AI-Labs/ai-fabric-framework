package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.manager.ConversationManagerComponentId;
import ai.fabric.execution.manager.ConversationManagerTargetResultProjector;

public final class AccountManagerReadResultProjector
    implements ConversationManagerTargetResultProjector<
        AccountDelegationCoordinatorRequest,
        AccountResolutionResult
    > {

    public static final ConversationManagerComponentId ID =
        ConversationManagerComponentId.of(
            "account-manager-read-result",
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
    public Class<AccountResolutionResult> targetOutputType() {
        return AccountResolutionResult.class;
    }

    @Override
    public String project(
        AccountDelegationCoordinatorRequest request,
        AIExecutionResult<AccountResolutionResult> targetExecution
    ) {
        AccountResolutionResult output = targetExecution.output();
        String summary = requireText(output.summary(), "summary");
        if (output.blockers().isEmpty()) {
            return summary;
        }
        String nextStep = output.blockers().getFirst()
            .recommendedNextStep();
        return nextStep == null || nextStep.isBlank()
            ? summary
            : summary + " Recommended next step: " + nextStep.trim();
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
