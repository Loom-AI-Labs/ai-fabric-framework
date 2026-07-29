package com.ai.fabric.realapps.agenticresolver.agentic.plan;

import ai.fabric.execution.plan.PlanComponentId;
import ai.fabric.execution.plan.PlanResultAggregator;
import ai.fabric.execution.plan.PlanStepOutputs;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolutionRequest;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolutionResult;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AccountReadinessResultAggregator
    implements PlanResultAggregator<
        AccountResolutionRequest,
        AccountResolutionResult
    > {

    @Override
    public PlanComponentId id() {
        return AccountResolverPlans.READINESS_RESULT;
    }

    @Override
    public Class<AccountResolutionRequest> planInputType() {
        return AccountResolutionRequest.class;
    }

    @Override
    public Class<AccountResolutionResult> outputType() {
        return AccountResolutionResult.class;
    }

    @Override
    public Map<String, Class<?>> requiredStepOutputs() {
        return Map.of(
            AccountResolverPlans.ACCOUNT_STATE_STEP,
            AccountResolutionResult.class
        );
    }

    @Override
    public AccountResolutionResult aggregate(
        AccountResolutionRequest planInput,
        PlanStepOutputs approvedOutputs
    ) {
        return approvedOutputs.require(
            AccountResolverPlans.ACCOUNT_STATE_STEP,
            AccountResolutionResult.class
        );
    }
}
