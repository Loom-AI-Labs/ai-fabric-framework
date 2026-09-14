package dev.aifabric.examples.consumer;

import ai.fabric.execution.chain.SpecialistChainComponentId;
import ai.fabric.execution.chain.SpecialistChainConversationPolicy;
import ai.fabric.execution.chain.SpecialistChainDefinition;
import ai.fabric.execution.chain.SpecialistChainId;
import ai.fabric.execution.chain.SpecialistChainInputAdapter;
import ai.fabric.execution.chain.SpecialistChainLimits;
import ai.fabric.execution.chain.SpecialistChainResultProjection;
import ai.fabric.execution.chain.SpecialistChainTarget;
import ai.fabric.execution.chain.SpecialistChainTargetInputMapper;
import ai.fabric.execution.chain.SpecialistChainTargetRequest;
import ai.fabric.execution.chain.SpecialistChainTargetResultProjector;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.specialist.SpecialistId;
import dev.aifabric.examples.consumer.SupportPlanConfiguration.AccountSnapshot;
import dev.aifabric.examples.consumer.SupportPlanConfiguration.PolicySnapshot;
import dev.aifabric.examples.consumer.SupportPlanConfiguration.SupportRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SupportChainConfiguration {

    public static final SpecialistChainId SUPPORT_CHAIN =
        SpecialistChainId.of("consumer-support-chain", "1");
    public static final SpecialistId SUPPORT_MANAGER =
        SpecialistId.of("consumer-support-manager", "1");
    public static final SpecialistChainComponentId CHAIN_INPUT =
        SpecialistChainComponentId.of("consumer-chain-input", "1");
    public static final SpecialistChainComponentId ACCOUNT_INPUT =
        SpecialistChainComponentId.of("consumer-chain-account-input", "1");
    public static final SpecialistChainComponentId POLICY_INPUT =
        SpecialistChainComponentId.of("consumer-chain-policy-input", "1");
    public static final SpecialistChainComponentId ACCOUNT_RESULT =
        SpecialistChainComponentId.of("consumer-chain-account-result", "1");
    public static final SpecialistChainComponentId POLICY_RESULT =
        SpecialistChainComponentId.of("consumer-chain-policy-result", "1");

    @Bean
    SpecialistChainInputAdapter<SupportRequest> supportChainInputAdapter() {
        return new SupportInputAdapter();
    }

    @Bean
    SpecialistChainTargetInputMapper<SupportRequest, String>
        accountChainInputMapper() {
        return new QuestionTargetMapper(ACCOUNT_INPUT);
    }

    @Bean
    SpecialistChainTargetInputMapper<SupportRequest, String>
        policyChainInputMapper() {
        return new QuestionTargetMapper(POLICY_INPUT);
    }

    @Bean
    SpecialistChainTargetResultProjector<SupportRequest, AccountSnapshot>
        accountChainResultProjector() {
        return new AccountResultProjector();
    }

    @Bean
    SpecialistChainTargetResultProjector<SupportRequest, PolicySnapshot>
        policyChainResultProjector() {
        return new PolicyResultProjector();
    }

    @Bean
    SpecialistChainDefinition<SupportRequest> supportChain(
        SpecialistChainInputAdapter<SupportRequest> inputAdapter,
        SpecialistChainTargetInputMapper<SupportRequest, String>
            accountChainInputMapper,
        SpecialistChainTargetResultProjector<SupportRequest, AccountSnapshot>
            accountChainResultProjector,
        SpecialistChainTargetInputMapper<SupportRequest, String>
            policyChainInputMapper,
        SpecialistChainTargetResultProjector<SupportRequest, PolicySnapshot>
            policyChainResultProjector
    ) {
        return new SpecialistChainDefinition<>(
            SUPPORT_CHAIN,
            SUPPORT_MANAGER,
            SupportRequest.class,
            inputAdapter,
            List.of(
                new SpecialistChainTarget<>(
                    SupportPlanConfiguration.ACCOUNT_READER,
                    "Read approved account status and payment readiness.",
                    accountChainInputMapper,
                    accountChainResultProjector,
                    true,
                    true,
                    false
                ),
                new SpecialistChainTarget<>(
                    SupportPlanConfiguration.POLICY_READER,
                    "Read the approved account payment policy.",
                    policyChainInputMapper,
                    policyChainResultProjector,
                    true,
                    true,
                    false
                )
            ),
            new SpecialistChainLimits(
                Duration.ofSeconds(20),
                3,
                2,
                2,
                1,
                2_000
            ),
            SpecialistChainConversationPolicy.DISABLED
        );
    }

    private static final class SupportInputAdapter
        implements SpecialistChainInputAdapter<SupportRequest> {

        @Override
        public SpecialistChainComponentId id() {
            return CHAIN_INPUT;
        }

        @Override
        public Class<SupportRequest> inputType() {
            return SupportRequest.class;
        }

        @Override
        public String currentUserMessage(SupportRequest input) {
            return input.question();
        }
    }

    private record QuestionTargetMapper(SpecialistChainComponentId id)
        implements SpecialistChainTargetInputMapper<SupportRequest, String> {

        @Override
        public Class<SupportRequest> chainRequestType() {
            return SupportRequest.class;
        }

        @Override
        public Class<String> targetInputType() {
            return String.class;
        }

        @Override
        public String map(
            SupportRequest chainRequest,
            SpecialistChainTargetRequest targetRequest
        ) {
            return chainRequest.question();
        }
    }

    private static final class AccountResultProjector
        implements SpecialistChainTargetResultProjector<
            SupportRequest,
            AccountSnapshot
        > {

        @Override
        public SpecialistChainComponentId id() {
            return ACCOUNT_RESULT;
        }

        @Override
        public Class<SupportRequest> chainRequestType() {
            return SupportRequest.class;
        }

        @Override
        public Class<AccountSnapshot> targetOutputType() {
            return AccountSnapshot.class;
        }

        @Override
        public SpecialistChainResultProjection project(
            SupportRequest chainRequest,
            AIExecutionResult<AccountSnapshot> targetExecution
        ) {
            AccountSnapshot output = targetExecution.output();
            return new SpecialistChainResultProjection(
                "Approved account state was read.",
                Map.of(
                    "status",
                    output.status(),
                    "verifiedPaymentMethod",
                    Boolean.toString(output.verifiedPaymentMethod())
                ),
                List.of()
            );
        }
    }

    private static final class PolicyResultProjector
        implements SpecialistChainTargetResultProjector<
            SupportRequest,
            PolicySnapshot
        > {

        @Override
        public SpecialistChainComponentId id() {
            return POLICY_RESULT;
        }

        @Override
        public Class<SupportRequest> chainRequestType() {
            return SupportRequest.class;
        }

        @Override
        public Class<PolicySnapshot> targetOutputType() {
            return PolicySnapshot.class;
        }

        @Override
        public SpecialistChainResultProjection project(
            SupportRequest chainRequest,
            AIExecutionResult<PolicySnapshot> targetExecution
        ) {
            PolicySnapshot output = targetExecution.output();
            return new SpecialistChainResultProjection(
                "Approved payment policy was read.",
                Map.of(
                    "paymentRequired",
                    Boolean.toString(output.paymentRequired()),
                    "policy",
                    output.policy()
                ),
                List.of()
            );
        }
    }
}
