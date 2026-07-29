package com.ai.fabric.realapps.agenticresolver.agentic.plan;

import ai.fabric.execution.plan.PlanComponentId;
import ai.fabric.execution.plan.PlanStepInputMapper;
import ai.fabric.execution.plan.PlanStepOutputs;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolutionRequest;
import org.springframework.stereotype.Component;

@Component
public class AccountBillingAccountInputMapper
    implements PlanStepInputMapper<
        AccountBillingResolutionPlanRequest,
        AccountResolutionRequest
    > {

    @Override
    public PlanComponentId id() {
        return AccountResolverPlans.BILLING_ACCOUNT_INPUT;
    }

    @Override
    public Class<AccountBillingResolutionPlanRequest> planInputType() {
        return AccountBillingResolutionPlanRequest.class;
    }

    @Override
    public Class<AccountResolutionRequest> stepInputType() {
        return AccountResolutionRequest.class;
    }

    @Override
    public AccountResolutionRequest map(
        AccountBillingResolutionPlanRequest planInput,
        PlanStepOutputs approvedOutputs
    ) {
        if (approvedOutputs.size() != 0) {
            throw new IllegalArgumentException(
                "The initial account step must not receive predecessor outputs"
            );
        }
        return new AccountResolutionRequest(planInput.question());
    }
}
