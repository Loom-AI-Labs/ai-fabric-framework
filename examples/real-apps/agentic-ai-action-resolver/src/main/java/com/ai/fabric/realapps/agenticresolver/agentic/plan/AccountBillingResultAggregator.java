package com.ai.fabric.realapps.agenticresolver.agentic.plan;

import ai.fabric.execution.plan.PlanComponentId;
import ai.fabric.execution.plan.PlanResultAggregator;
import ai.fabric.execution.plan.PlanStepOutputs;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolutionResult;
import com.ai.fabric.realapps.agenticresolver.agentic.BillingResolutionAssessmentResult;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AccountBillingResultAggregator
    implements PlanResultAggregator<
        AccountBillingResolutionPlanRequest,
        AccountBillingResolutionPlanResult
    > {

    @Override
    public PlanComponentId id() {
        return AccountResolverPlans.BILLING_RESULT;
    }

    @Override
    public Class<AccountBillingResolutionPlanRequest> planInputType() {
        return AccountBillingResolutionPlanRequest.class;
    }

    @Override
    public Class<AccountBillingResolutionPlanResult> outputType() {
        return AccountBillingResolutionPlanResult.class;
    }

    @Override
    public Map<String, Class<?>> requiredStepOutputs() {
        return Map.of(
            AccountResolverPlans.ACCOUNT_STATE_STEP,
            AccountResolutionResult.class,
            AccountResolverPlans.BILLING_PATH_STEP,
            BillingResolutionAssessmentResult.class
        );
    }

    @Override
    public AccountBillingResolutionPlanResult aggregate(
        AccountBillingResolutionPlanRequest planInput,
        PlanStepOutputs approvedOutputs
    ) {
        AccountResolutionResult account = approvedOutputs.require(
            AccountResolverPlans.ACCOUNT_STATE_STEP,
            AccountResolutionResult.class
        );
        BillingResolutionAssessmentResult billing =
            approvedOutputs.require(
                AccountResolverPlans.BILLING_PATH_STEP,
                BillingResolutionAssessmentResult.class
            );
        return new AccountBillingResolutionPlanResult(
            account.assessment(),
            account.summary(),
            account.blockers(),
            billing.decision(),
            billing.expectedStatus(),
            billing.automaticLimit(),
            billing.explanation()
        );
    }
}
