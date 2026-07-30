package dev.aifabric.examples.consumer;

import ai.fabric.execution.plan.ExecutionPlanDefinition;
import ai.fabric.execution.plan.ExecutionPlanId;
import ai.fabric.execution.plan.FanInPolicy;
import ai.fabric.execution.plan.ParallelPlanStep;
import ai.fabric.execution.plan.PlanComponentId;
import ai.fabric.execution.plan.PlanResultAggregator;
import ai.fabric.execution.plan.PlanStage;
import ai.fabric.execution.plan.PlanStepInputMapper;
import ai.fabric.execution.plan.PlanStepOutputs;
import ai.fabric.execution.plan.SpecialistPlanStep;
import ai.fabric.execution.specialist.SpecialistId;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SupportPlanConfiguration {

    public static final SpecialistId ACCOUNT_READER =
        SpecialistId.of("consumer-account-reader", "1");
    public static final SpecialistId POLICY_READER =
        SpecialistId.of("consumer-policy-reader", "1");
    public static final PlanComponentId ACCOUNT_INPUT =
        PlanComponentId.of("consumer-account-input", "1");
    public static final PlanComponentId POLICY_INPUT =
        PlanComponentId.of("consumer-policy-input", "1");
    public static final PlanComponentId RESULT_AGGREGATOR =
        PlanComponentId.of("consumer-support-result", "1");
    public static final ExecutionPlanId SEQUENTIAL_PLAN =
        ExecutionPlanId.of("consumer-support-sequential", "1");
    public static final ExecutionPlanId PARALLEL_PLAN =
        ExecutionPlanId.of("consumer-support-parallel", "1");

    @Bean
    public PlanStepInputMapper<SupportRequest, String> accountInputMapper() {
        return new QuestionMapper(ACCOUNT_INPUT);
    }

    @Bean
    public PlanStepInputMapper<SupportRequest, String> policyInputMapper() {
        return new QuestionMapper(POLICY_INPUT);
    }

    @Bean
    public PlanResultAggregator<SupportRequest, SupportDecision>
        supportResultAggregator() {
        return new SupportResultAggregator();
    }

    @Bean
    public ExecutionPlanDefinition<SupportRequest, SupportDecision>
        sequentialSupportPlan() {
        return plan(SEQUENTIAL_PLAN, List.of(
            accountBranch(),
            policyBranch()
        ));
    }

    @Bean
    public ExecutionPlanDefinition<SupportRequest, SupportDecision>
        parallelSupportPlan() {
        return plan(
            PARALLEL_PLAN,
            List.of(new ParallelPlanStep(
                "independent-readers",
                List.of(accountBranch(), policyBranch()),
                FanInPolicy.ALL_REQUIRED,
                2
            ))
        );
    }

    private ExecutionPlanDefinition<SupportRequest, SupportDecision> plan(
        ExecutionPlanId id,
        List<? extends PlanStage> stages
    ) {
        return new ExecutionPlanDefinition<>(
            id,
            SupportRequest.class,
            SupportDecision.class,
            stages,
            RESULT_AGGREGATOR,
            Duration.ofSeconds(20)
        );
    }

    private SpecialistPlanStep accountBranch() {
        return new SpecialistPlanStep(
            "account-state",
            ACCOUNT_READER,
            String.class,
            AccountSnapshot.class,
            ACCOUNT_INPUT
        );
    }

    private SpecialistPlanStep policyBranch() {
        return new SpecialistPlanStep(
            "policy-state",
            POLICY_READER,
            String.class,
            PolicySnapshot.class,
            POLICY_INPUT
        );
    }

    public record SupportRequest(String question) {}

    public record AccountSnapshot(
        String status,
        boolean verifiedPaymentMethod
    ) {}

    public record PolicySnapshot(
        boolean paymentRequired,
        String policy
    ) {}

    public record SupportDecision(
        boolean canContinue,
        String explanation
    ) {}

    private record QuestionMapper(
        PlanComponentId id
    ) implements PlanStepInputMapper<SupportRequest, String> {

        @Override
        public Class<SupportRequest> planInputType() {
            return SupportRequest.class;
        }

        @Override
        public Class<String> stepInputType() {
            return String.class;
        }

        @Override
        public String map(
            SupportRequest planInput,
            PlanStepOutputs approvedOutputs
        ) {
            if (approvedOutputs.size() != 0) {
                throw new IllegalArgumentException(
                    "Independent readers cannot consume sibling output"
                );
            }
            return planInput.question();
        }
    }

    private static final class SupportResultAggregator
        implements PlanResultAggregator<SupportRequest, SupportDecision> {

        @Override
        public PlanComponentId id() {
            return RESULT_AGGREGATOR;
        }

        @Override
        public Class<SupportRequest> planInputType() {
            return SupportRequest.class;
        }

        @Override
        public Class<SupportDecision> outputType() {
            return SupportDecision.class;
        }

        @Override
        public Map<String, Class<?>> requiredStepOutputs() {
            return Map.of(
                "account-state",
                AccountSnapshot.class,
                "policy-state",
                PolicySnapshot.class
            );
        }

        @Override
        public SupportDecision aggregate(
            SupportRequest planInput,
            PlanStepOutputs approvedOutputs
        ) {
            AccountSnapshot account = approvedOutputs.require(
                "account-state",
                AccountSnapshot.class
            );
            PolicySnapshot policy = approvedOutputs.require(
                "policy-state",
                PolicySnapshot.class
            );
            boolean canContinue = "ACTIVE".equals(account.status())
                && (
                    !policy.paymentRequired()
                    || account.verifiedPaymentMethod()
                );
            return new SupportDecision(
                canContinue,
                canContinue
                    ? "The account satisfies the approved payment policy."
                    : policy.policy()
            );
        }
    }
}
