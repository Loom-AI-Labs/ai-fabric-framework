package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.chain.SpecialistChainComponentId;
import ai.fabric.execution.chain.SpecialistChainTargetInputMapper;
import ai.fabric.execution.chain.SpecialistChainTargetRequest;

public final class AccountReadChainInputMapper
    implements SpecialistChainTargetInputMapper<
        AccountDelegationCoordinatorRequest,
        AccountResolutionRequest
    > {

    static final String SCOPED_QUESTION =
        "Inspect only the current account readiness and explain any blockers. "
            + "Do not assess a refund or account credit.";

    public static final SpecialistChainComponentId ID =
        SpecialistChainComponentId.of("account-chain-read-input", "1");

    @Override
    public SpecialistChainComponentId id() {
        return ID;
    }

    @Override
    public Class<AccountDelegationCoordinatorRequest> chainRequestType() {
        return AccountDelegationCoordinatorRequest.class;
    }

    @Override
    public Class<AccountResolutionRequest> targetInputType() {
        return AccountResolutionRequest.class;
    }

    @Override
    public AccountResolutionRequest map(
        AccountDelegationCoordinatorRequest request,
        SpecialistChainTargetRequest targetRequest
    ) {
        return new AccountResolutionRequest(SCOPED_QUESTION);
    }
}
