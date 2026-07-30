package com.ai.fabric.realapps.agenticresolver.agentic.plan;

import ai.fabric.execution.plan.ExecutionPlanDefinition;
import ai.fabric.execution.plan.FanInPolicy;
import ai.fabric.execution.plan.ParallelPlanStep;
import ai.fabric.execution.plan.PlanStage;
import ai.fabric.execution.plan.SpecialistPlanStep;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolutionRequest;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolutionResult;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolverSpecialists;
import com.ai.fabric.realapps.agenticresolver.agentic.BillingResolutionAssessmentRequest;
import com.ai.fabric.realapps.agenticresolver.agentic.BillingResolutionAssessmentResult;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AccountResolverPlanConfiguration {

    @Bean
    public ExecutionPlanDefinition<
        AccountResolutionRequest,
        AccountResolutionResult
    > accountReadinessPlan() {
        return new ExecutionPlanDefinition<>(
            AccountResolverPlans.ACCOUNT_READINESS,
            AccountResolutionRequest.class,
            AccountResolutionResult.class,
            List.of(new SpecialistPlanStep(
                AccountResolverPlans.ACCOUNT_STATE_STEP,
                AccountResolverSpecialists.READ_SPECIALIST_ID,
                AccountResolutionRequest.class,
                AccountResolutionResult.class,
                AccountResolverPlans.READINESS_INPUT
            )),
            AccountResolverPlans.READINESS_RESULT,
            Duration.ofSeconds(30)
        );
    }

    @Bean
    public ExecutionPlanDefinition<
        AccountBillingResolutionPlanRequest,
        AccountBillingResolutionPlanResult
    > accountBillingResolutionPlan() {
        return new ExecutionPlanDefinition<>(
            AccountResolverPlans.ACCOUNT_BILLING_RESOLUTION,
            AccountBillingResolutionPlanRequest.class,
            AccountBillingResolutionPlanResult.class,
            List.of(
                new SpecialistPlanStep(
                    AccountResolverPlans.ACCOUNT_STATE_STEP,
                    AccountResolverSpecialists.READ_SPECIALIST_ID,
                    AccountResolutionRequest.class,
                    AccountResolutionResult.class,
                    AccountResolverPlans.BILLING_ACCOUNT_INPUT
                ),
                new SpecialistPlanStep(
                    AccountResolverPlans.BILLING_PATH_STEP,
                    AccountResolverSpecialists.BILLING_ADVISOR_SPECIALIST_ID,
                    BillingResolutionAssessmentRequest.class,
                    BillingResolutionAssessmentResult.class,
                    AccountResolverPlans.BILLING_ASSESSMENT_INPUT
                )
            ),
            AccountResolverPlans.BILLING_RESULT,
            Duration.ofSeconds(45)
        );
    }

    @Bean
    public ExecutionPlanDefinition<
        AccountBillingResolutionPlanRequest,
        AccountBillingResolutionPlanResult
    > accountBillingIndependentSequentialPlan() {
        return independentBillingPlan(
            AccountResolverPlans.ACCOUNT_BILLING_INDEPENDENT_SEQUENTIAL,
            List.of(accountBranch(), independentBillingBranch())
        );
    }

    @Bean
    public ExecutionPlanDefinition<
        AccountBillingResolutionPlanRequest,
        AccountBillingResolutionPlanResult
    > accountBillingIndependentParallelPlan() {
        return independentBillingPlan(
            AccountResolverPlans.ACCOUNT_BILLING_INDEPENDENT_PARALLEL,
            List.of(new ParallelPlanStep(
                AccountResolverPlans.INDEPENDENT_READERS_STAGE,
                List.of(accountBranch(), independentBillingBranch()),
                FanInPolicy.ALL_REQUIRED,
                2
            ))
        );
    }

    private ExecutionPlanDefinition<
        AccountBillingResolutionPlanRequest,
        AccountBillingResolutionPlanResult
    > independentBillingPlan(
        ai.fabric.execution.plan.ExecutionPlanId id,
        List<? extends PlanStage> stages
    ) {
        return new ExecutionPlanDefinition<>(
            id,
            AccountBillingResolutionPlanRequest.class,
            AccountBillingResolutionPlanResult.class,
            stages,
            AccountResolverPlans.BILLING_RESULT,
            Duration.ofSeconds(45)
        );
    }

    private SpecialistPlanStep accountBranch() {
        return new SpecialistPlanStep(
            AccountResolverPlans.ACCOUNT_STATE_STEP,
            AccountResolverSpecialists.READ_SPECIALIST_ID,
            AccountResolutionRequest.class,
            AccountResolutionResult.class,
            AccountResolverPlans.BILLING_ACCOUNT_INPUT
        );
    }

    private SpecialistPlanStep independentBillingBranch() {
        return new SpecialistPlanStep(
            AccountResolverPlans.BILLING_PATH_STEP,
            AccountResolverSpecialists.BILLING_ADVISOR_SPECIALIST_ID,
            BillingResolutionAssessmentRequest.class,
            BillingResolutionAssessmentResult.class,
            AccountResolverPlans.BILLING_INDEPENDENT_ASSESSMENT_INPUT
        );
    }
}
