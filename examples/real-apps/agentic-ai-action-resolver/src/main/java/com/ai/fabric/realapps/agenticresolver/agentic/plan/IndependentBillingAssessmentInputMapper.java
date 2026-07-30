package com.ai.fabric.realapps.agenticresolver.agentic.plan;

import ai.fabric.execution.plan.PlanComponentId;
import ai.fabric.execution.plan.PlanStepInputMapper;
import ai.fabric.execution.plan.PlanStepOutputs;
import com.ai.fabric.realapps.agenticresolver.agentic.BillingResolutionAssessmentRequest;
import org.springframework.stereotype.Component;

@Component
public class IndependentBillingAssessmentInputMapper
    implements PlanStepInputMapper<
        AccountBillingResolutionPlanRequest,
        BillingResolutionAssessmentRequest
    > {

    @Override
    public PlanComponentId id() {
        return AccountResolverPlans.BILLING_INDEPENDENT_ASSESSMENT_INPUT;
    }

    @Override
    public Class<AccountBillingResolutionPlanRequest> planInputType() {
        return AccountBillingResolutionPlanRequest.class;
    }

    @Override
    public Class<BillingResolutionAssessmentRequest> stepInputType() {
        return BillingResolutionAssessmentRequest.class;
    }

    @Override
    public BillingResolutionAssessmentRequest map(
        AccountBillingResolutionPlanRequest planInput,
        PlanStepOutputs approvedOutputs
    ) {
        if (approvedOutputs.size() != 0) {
            throw new IllegalArgumentException(
                "An independent billing branch cannot receive sibling outputs"
            );
        }
        return new BillingResolutionAssessmentRequest(
            planInput.question(),
            planInput.resolutionType(),
            planInput.amount()
        );
    }
}
