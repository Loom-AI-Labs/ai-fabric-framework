package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.chain.SpecialistChainId;

public final class AccountSpecialistChains {

    public static final SpecialistChainId SMART_RESOLUTION =
        SpecialistChainId.of("account-smart-resolution", "1");

    public static final SpecialistChainId DECLARATIVE_RESOLUTION =
        SpecialistChainId.of("account-declarative-resolution", "1");

    private AccountSpecialistChains() {}
}
