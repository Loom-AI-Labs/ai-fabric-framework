package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.chain.SpecialistChainId;

public final class IncidentSpecialistChains {

    public static final SpecialistChainId SMART_INVESTIGATION =
        SpecialistChainId.of("incident-smart-investigation", "1");

    public static final SpecialistChainId DECLARATIVE_INVESTIGATION =
        SpecialistChainId.of("incident-declarative-investigation", "1");

    private IncidentSpecialistChains() {}
}
