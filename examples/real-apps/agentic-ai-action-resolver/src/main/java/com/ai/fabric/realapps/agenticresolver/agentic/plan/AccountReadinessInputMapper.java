package com.ai.fabric.realapps.agenticresolver.agentic.plan;

import ai.fabric.execution.plan.PlanComponentId;
import ai.fabric.execution.plan.PlanStepInputMapper;
import ai.fabric.execution.plan.PlanStepOutputs;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolutionRequest;
import org.springframework.stereotype.Component;

@Component
public class AccountReadinessInputMapper
    implements PlanStepInputMapper<
        AccountResolutionRequest,
        AccountResolutionRequest
    > {

    @Override
    public PlanComponentId id() {
        return AccountResolverPlans.READINESS_INPUT;
    }

    @Override
    public Class<AccountResolutionRequest> planInputType() {
        return AccountResolutionRequest.class;
    }

    @Override
    public Class<AccountResolutionRequest> stepInputType() {
        return AccountResolutionRequest.class;
    }

    @Override
    public AccountResolutionRequest map(
        AccountResolutionRequest planInput,
        PlanStepOutputs approvedOutputs
    ) {
        if (approvedOutputs.size() != 0) {
            throw new IllegalArgumentException(
                "The initial readiness step must not receive predecessor outputs"
            );
        }
        return planInput;
    }
}
