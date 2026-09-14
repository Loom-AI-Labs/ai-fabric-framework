package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.chain.SpecialistChainConversationPolicy;
import ai.fabric.execution.chain.SpecialistChainDefinition;
import ai.fabric.execution.chain.SpecialistChainLimits;
import ai.fabric.execution.chain.SpecialistChainTarget;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "ai.execution.specialist-chains.enabled",
    havingValue = "true"
)
public class AccountSpecialistChainConfiguration {

    @Bean
    AccountChainInputAdapter accountChainInputAdapter() {
        return new AccountChainInputAdapter();
    }

    @Bean
    AccountReadChainInputMapper accountReadChainInputMapper() {
        return new AccountReadChainInputMapper();
    }

    @Bean
    AccountBillingChainInputMapper accountBillingChainInputMapper() {
        return new AccountBillingChainInputMapper();
    }

    @Bean
    AccountReadChainResultProjector accountReadChainResultProjector() {
        return new AccountReadChainResultProjector();
    }

    @Bean
    AccountBillingChainResultProjector accountBillingChainResultProjector() {
        return new AccountBillingChainResultProjector();
    }

    @Bean
    SpecialistChainDefinition<AccountDelegationCoordinatorRequest>
        accountSmartResolutionChain(
            AccountChainInputAdapter inputAdapter,
            AccountReadChainInputMapper accountInput,
            AccountReadChainResultProjector accountResult,
            AccountBillingChainInputMapper billingInput,
            AccountBillingChainResultProjector billingResult
        ) {
        return new SpecialistChainDefinition<>(
            AccountSpecialistChains.SMART_RESOLUTION,
            AccountResolverSpecialists.CHAIN_MANAGER_ID,
            AccountDelegationCoordinatorRequest.class,
            inputAdapter,
            List.of(
                new SpecialistChainTarget<>(
                    AccountResolverSpecialists.MANAGER_READ_SPECIALIST_ID,
                    "Inspect backend-owned account readiness, blockers, "
                        + "subscription state, payment readiness, and address "
                        + "readiness using approved profile and policy evidence.",
                    accountInput,
                    accountResult,
                    true,
                    true,
                    true
                ),
                new SpecialistChainTarget<>(
                    AccountResolverSpecialists.MANAGER_BILLING_ADVISOR_ID,
                    "Assess a supplied refund or account-credit type and amount "
                        + "against approved billing policy without creating a write.",
                    billingInput,
                    billingResult,
                    true,
                    true,
                    true
                )
            ),
            new SpecialistChainLimits(
                Duration.ofSeconds(70),
                4,
                2,
                2,
                1,
                8_000
            ),
            SpecialistChainConversationPolicy.REQUIRED
        );
    }
}
