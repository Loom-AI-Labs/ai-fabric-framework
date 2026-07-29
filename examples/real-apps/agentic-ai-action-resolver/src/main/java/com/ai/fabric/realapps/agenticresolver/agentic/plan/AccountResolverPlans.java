package com.ai.fabric.realapps.agenticresolver.agentic.plan;

import ai.fabric.execution.plan.ExecutionPlanId;
import ai.fabric.execution.plan.PlanComponentId;

public final class AccountResolverPlans {

    public static final ExecutionPlanId ACCOUNT_READINESS =
        ExecutionPlanId.of("account-readiness", "1");
    public static final ExecutionPlanId ACCOUNT_BILLING_RESOLUTION =
        ExecutionPlanId.of("account-billing-resolution", "1");

    static final PlanComponentId READINESS_INPUT =
        PlanComponentId.of("account-readiness-input", "1");
    static final PlanComponentId READINESS_RESULT =
        PlanComponentId.of("account-readiness-result", "1");
    static final PlanComponentId BILLING_ACCOUNT_INPUT =
        PlanComponentId.of("account-billing-account-input", "1");
    static final PlanComponentId BILLING_ASSESSMENT_INPUT =
        PlanComponentId.of("account-billing-assessment-input", "1");
    static final PlanComponentId BILLING_RESULT =
        PlanComponentId.of("account-billing-result", "1");

    static final String ACCOUNT_STATE_STEP = "account-state";
    static final String BILLING_PATH_STEP = "billing-path";

    private AccountResolverPlans() {}
}
