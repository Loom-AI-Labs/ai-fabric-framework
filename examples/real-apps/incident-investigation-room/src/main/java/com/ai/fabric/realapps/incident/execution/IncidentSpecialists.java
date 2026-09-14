package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.specialist.SpecialistId;

public final class IncidentSpecialists {

    public static final SpecialistId SERVICE_HEALTH =
        SpecialistId.of("service-health-reader", "1");
    public static final SpecialistId CHANGE_RISK =
        SpecialistId.of("change-risk-reader", "1");
    public static final SpecialistId INTAKE =
        SpecialistId.of("incident-intake", "1");
    public static final SpecialistId CONVERSATION_MANAGER =
        SpecialistId.of("incident-conversation-manager", "1");
    public static final SpecialistId SERVICE_HEALTH_V2 =
        SpecialistId.of("service-health-reader", "2");
    public static final SpecialistId CHANGE_RISK_V2 =
        SpecialistId.of("change-risk-reader", "2");
    public static final SpecialistId INTAKE_V2 =
        SpecialistId.of("incident-intake", "2");
    public static final SpecialistId CONVERSATION_MANAGER_V2 =
        SpecialistId.of("incident-conversation-manager", "2");
    public static final SpecialistId CHAIN_MANAGER_V3 =
        SpecialistId.of("incident-chain-manager", "3");

    private IncidentSpecialists() {}
}
