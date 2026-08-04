package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.plan.ExecutionPlanId;
import ai.fabric.execution.plan.PlanComponentId;

public final class IncidentPlans {

    public static final ExecutionPlanId SEQUENTIAL = ExecutionPlanId.of(
        "incident-investigation-sequential",
        "1"
    );
    public static final ExecutionPlanId PARALLEL = ExecutionPlanId.of(
        "incident-investigation-parallel",
        "1"
    );
    public static final String SERVICE_HEALTH_STEP = "service-health";
    public static final String CHANGE_RISK_STEP = "change-risk";
    public static final String PARALLEL_STAGE = "independent-readers";
    public static final PlanComponentId SERVICE_HEALTH_INPUT =
        PlanComponentId.of("incident-service-health-input", "1");
    public static final PlanComponentId CHANGE_RISK_INPUT =
        PlanComponentId.of("incident-change-risk-input", "1");
    public static final PlanComponentId ASSESSMENT_RESULT =
        PlanComponentId.of("incident-assessment-result", "1");

    private IncidentPlans() {}
}
