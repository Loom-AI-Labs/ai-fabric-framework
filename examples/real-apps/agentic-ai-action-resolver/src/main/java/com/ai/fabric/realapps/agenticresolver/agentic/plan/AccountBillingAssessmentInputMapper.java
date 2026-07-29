package com.ai.fabric.realapps.agenticresolver.agentic.plan;

import ai.fabric.execution.plan.PlanComponentId;
import ai.fabric.execution.plan.PlanStepInputMapper;
import ai.fabric.execution.plan.PlanStepOutputs;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolutionResult;
import com.ai.fabric.realapps.agenticresolver.agentic.BillingResolutionAssessmentRequest;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class AccountBillingAssessmentInputMapper
    implements PlanStepInputMapper<
        AccountBillingResolutionPlanRequest,
        BillingResolutionAssessmentRequest
    > {

    private static final int MAX_QUESTION_CHARACTERS = 1_900;

    @Override
    public PlanComponentId id() {
        return AccountResolverPlans.BILLING_ASSESSMENT_INPUT;
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
    public Map<String, Class<?>> requiredStepOutputs() {
        return Map.of(
            AccountResolverPlans.ACCOUNT_STATE_STEP,
            AccountResolutionResult.class
        );
    }

    @Override
    public BillingResolutionAssessmentRequest map(
        AccountBillingResolutionPlanRequest planInput,
        PlanStepOutputs approvedOutputs
    ) {
        AccountResolutionResult account = approvedOutputs.require(
            AccountResolverPlans.ACCOUNT_STATE_STEP,
            AccountResolutionResult.class
        );
        String blockerRequirements = account.blockers().stream()
            .map(blocker -> blocker.requirement().name())
            .distinct()
            .sorted()
            .collect(Collectors.joining(","));
        if (blockerRequirements.isBlank()) {
            blockerRequirements = "NONE";
        }
        String question = String.join(
            "\n",
            planInput.question(),
            "Validated predecessor account assessment: "
                + account.assessment().name(),
            "Validated blocker requirements: " + blockerRequirements
        );
        if (question.length() > MAX_QUESTION_CHARACTERS) {
            question = question.substring(0, MAX_QUESTION_CHARACTERS);
        }
        return new BillingResolutionAssessmentRequest(
            question,
            planInput.resolutionType(),
            planInput.amount()
        );
    }
}
