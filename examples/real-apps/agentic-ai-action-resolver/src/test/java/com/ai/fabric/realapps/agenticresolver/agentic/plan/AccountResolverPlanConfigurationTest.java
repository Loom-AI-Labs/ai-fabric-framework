package com.ai.fabric.realapps.agenticresolver.agentic.plan;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.execution.plan.ExecutionPlanDefinition;
import ai.fabric.execution.plan.FanInPolicy;
import ai.fabric.execution.plan.ParallelPlanStep;
import ai.fabric.execution.plan.PlanStepOutputs;
import ai.fabric.execution.plan.SpecialistPlanStep;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolutionRequest;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolutionResult;
import com.ai.fabric.realapps.agenticresolver.agentic.BillingResolutionAssessmentRequest;
import com.ai.fabric.realapps.agenticresolver.agentic.BillingResolutionAssessmentResult;
import com.ai.fabric.realapps.agenticresolver.entity.RefundRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AccountResolverPlanConfigurationTest {

    @Test
    void declaresExistingAndEquivalentIndependentPlanTopologies() {
        AccountResolverPlanConfiguration configuration =
            new AccountResolverPlanConfiguration();

        ExecutionPlanDefinition<?, ?> readiness =
            configuration.accountReadinessPlan();
        ExecutionPlanDefinition<?, ?> billing =
            configuration.accountBillingResolutionPlan();
        ExecutionPlanDefinition<?, ?> sequential =
            configuration.accountBillingIndependentSequentialPlan();
        ExecutionPlanDefinition<?, ?> parallel =
            configuration.accountBillingIndependentParallelPlan();

        assertThat(readiness.id())
            .isEqualTo(AccountResolverPlans.ACCOUNT_READINESS);
        assertThat(readiness.steps()).singleElement()
            .isInstanceOfSatisfying(
                SpecialistPlanStep.class,
                step -> {
                    assertThat(step.id()).isEqualTo("account-state");
                    assertThat(step.inputType())
                        .isEqualTo(AccountResolutionRequest.class);
                    assertThat(step.outputType())
                        .isEqualTo(AccountResolutionResult.class);
                }
            );
        assertThat(billing.id())
            .isEqualTo(AccountResolverPlans.ACCOUNT_BILLING_RESOLUTION);
        assertThat(billing.steps()).extracting("id")
            .containsExactly("account-state", "billing-path");
        SpecialistPlanStep billingStep =
            (SpecialistPlanStep) billing.steps().get(1);
        assertThat(billingStep.inputType())
            .isEqualTo(BillingResolutionAssessmentRequest.class);
        assertThat(billingStep.outputType())
            .isEqualTo(BillingResolutionAssessmentResult.class);

        assertThat(sequential.id()).isEqualTo(
            AccountResolverPlans.ACCOUNT_BILLING_INDEPENDENT_SEQUENTIAL
        );
        assertThat(sequential.steps())
            .allMatch(SpecialistPlanStep.class::isInstance)
            .extracting("id")
            .containsExactly("account-state", "billing-path");
        assertThat(parallel.id()).isEqualTo(
            AccountResolverPlans.ACCOUNT_BILLING_INDEPENDENT_PARALLEL
        );
        assertThat(parallel.steps()).singleElement()
            .isInstanceOfSatisfying(
                ParallelPlanStep.class,
                stage -> {
                    assertThat(stage.id())
                        .isEqualTo("independent-readers");
                    assertThat(stage.fanInPolicy())
                        .isEqualTo(FanInPolicy.ALL_REQUIRED);
                    assertThat(stage.maximumConcurrency()).isEqualTo(2);
                    assertThat(stage.branches()).extracting("id")
                        .containsExactly("account-state", "billing-path");
                }
            );
    }

    @Test
    void mapsOnlyTheApprovedAccountCheckpointIntoTheBillingStep() {
        AccountBillingAssessmentInputMapper mapper =
            new AccountBillingAssessmentInputMapper();
        AccountResolutionResult account = blockedAccount();
        AccountBillingResolutionPlanRequest request =
            new AccountBillingResolutionPlanRequest(
                "Can this account receive a refund?",
                RefundRequest.ResolutionType.REFUND,
                null
            );

        BillingResolutionAssessmentRequest mapped = mapper.map(
            request,
            new PlanStepOutputs(Map.of("account-state", account))
        );

        assertThat(mapped.resolutionType())
            .isEqualTo(RefundRequest.ResolutionType.REFUND);
        assertThat(mapped.amount()).isNull();
        assertThat(mapped.question())
            .contains("Can this account receive a refund?")
            .contains("Validated predecessor account assessment: BLOCKED")
            .contains(
                "Validated blocker requirements: VERIFIED_PAYMENT_METHOD"
            )
            .doesNotContain("tenantId", "subscriptionId", "userId");
    }

    @Test
    void mapsIndependentBillingInputWithoutSiblingOutput() {
        IndependentBillingAssessmentInputMapper mapper =
            new IndependentBillingAssessmentInputMapper();
        AccountBillingResolutionPlanRequest request =
            new AccountBillingResolutionPlanRequest(
                "Assess this account credit.",
                RefundRequest.ResolutionType.ACCOUNT_CREDIT,
                new BigDecimal("25.00")
            );

        BillingResolutionAssessmentRequest mapped = mapper.map(
            request,
            new PlanStepOutputs(Map.of())
        );

        assertThat(mapper.requiredStepOutputs()).isEmpty();
        assertThat(mapped.question())
            .isEqualTo("Assess this account credit.");
        assertThat(mapped.resolutionType())
            .isEqualTo(RefundRequest.ResolutionType.ACCOUNT_CREDIT);
        assertThat(mapped.amount()).isEqualByComparingTo("25.00");
    }

    @Test
    void aggregatesValidatedStepOutputsWithoutReinterpretingThem() {
        AccountResolutionResult account = blockedAccount();
        BillingResolutionAssessmentResult billing =
            new BillingResolutionAssessmentResult(
                RefundRequest.ResolutionType.REFUND,
                new BigDecimal("75.00"),
                BillingResolutionAssessmentResult.Decision.REVIEW_REQUIRED,
                BillingResolutionAssessmentResult.ExpectedStatus
                    .PENDING_REVIEW,
                new BigDecimal("50.00"),
                "The amount requires review."
            );
        AccountBillingResolutionPlanRequest request =
            new AccountBillingResolutionPlanRequest(
                "Assess this refund.",
                RefundRequest.ResolutionType.REFUND,
                new BigDecimal("75.00")
            );

        AccountBillingResolutionPlanResult result =
            new AccountBillingResultAggregator().aggregate(
                request,
                new PlanStepOutputs(Map.of(
                    "account-state",
                    account,
                    "billing-path",
                    billing
                ))
            );

        assertThat(result.accountAssessment())
            .isEqualTo(AccountResolutionResult.Assessment.BLOCKED);
        assertThat(result.accountBlockers())
            .isEqualTo(account.blockers());
        assertThat(result.billingDecision())
            .isEqualTo(
                BillingResolutionAssessmentResult.Decision.REVIEW_REQUIRED
            );
        assertThat(result.expectedBillingStatus())
            .isEqualTo(
                BillingResolutionAssessmentResult.ExpectedStatus
                    .PENDING_REVIEW
            );
        assertThat(result.automaticLimit()).isEqualByComparingTo("50.00");
    }

    private AccountResolutionResult blockedAccount() {
        return new AccountResolutionResult(
            AccountResolutionResult.Assessment.BLOCKED,
            "A verified payment method is required.",
            List.of(new AccountResolutionResult.Blocker(
                AccountResolutionResult.Requirement.VERIFIED_PAYMENT_METHOD,
                "The current account has no verified payment method.",
                "Add and verify a payment method."
            ))
        );
    }
}
