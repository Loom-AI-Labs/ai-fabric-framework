package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.manager.ConversationManagerComponentId;
import ai.fabric.execution.manager.ConversationManagerTargetInputMapper;

public final class AccountManagerReadInputMapper
    implements ConversationManagerTargetInputMapper<
        AccountDelegationCoordinatorRequest,
        AccountResolutionRequest
    > {

    private static final String ACCOUNT_READ_TASK =
        "Inspect the current backend-owned account profile against the "
            + "approved account-resolution policies and return readiness "
            + "and blockers.";

    public static final ConversationManagerComponentId ID =
        ConversationManagerComponentId.of(
            "account-manager-read-input",
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
    public Class<AccountResolutionRequest> targetInputType() {
        return AccountResolutionRequest.class;
    }

    @Override
    public AccountResolutionRequest map(
        AccountDelegationCoordinatorRequest request
    ) {
        return new AccountResolutionRequest(ACCOUNT_READ_TASK);
    }
}
