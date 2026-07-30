package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.manager.ConversationManagerDefinition;
import ai.fabric.execution.manager.ConversationManagerTarget;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AccountConversationManagerConfiguration {

    @Bean
    AccountConversationManagerInputAdapter
        accountConversationManagerInputAdapter() {
        return new AccountConversationManagerInputAdapter();
    }

    @Bean
    AccountManagerReadInputMapper accountManagerReadInputMapper() {
        return new AccountManagerReadInputMapper();
    }

    @Bean
    AccountManagerReadResultProjector
        accountManagerReadResultProjector() {
        return new AccountManagerReadResultProjector();
    }

    @Bean
    BillingManagerInputMapper billingManagerInputMapper() {
        return new BillingManagerInputMapper();
    }

    @Bean
    BillingManagerResultProjector billingManagerResultProjector() {
        return new BillingManagerResultProjector();
    }

    @Bean
    ConversationManagerDefinition<AccountDelegationCoordinatorRequest>
        accountResolutionConversationManager(
            AccountConversationManagerInputAdapter inputAdapter,
            AccountManagerReadInputMapper accountInputMapper,
            AccountManagerReadResultProjector accountResultProjector,
            BillingManagerInputMapper billingInputMapper,
            BillingManagerResultProjector billingResultProjector
        ) {
        return new ConversationManagerDefinition<>(
            AccountConversationManagers.ACCOUNT_RESOLUTION,
            AccountResolverSpecialists.CONVERSATION_MANAGER_ID,
            AccountDelegationCoordinatorRequest.class,
            inputAdapter,
            List.of(
                new ConversationManagerTarget<>(
                    AccountResolverSpecialists.MANAGER_READ_SPECIALIST_ID,
                    "Read the current account profile and policy evidence "
                        + "to explain readiness, blockers, subscription, "
                        + "payment-method, and address status.",
                    accountInputMapper,
                    accountResultProjector
                ),
                new ConversationManagerTarget<>(
                    AccountResolverSpecialists
                        .MANAGER_BILLING_ADVISOR_ID,
                    "Assess a complete refund or account-credit type and "
                        + "amount against application-owned billing policy "
                        + "without creating a write.",
                    billingInputMapper,
                    billingResultProjector
                )
            ),
            Duration.ofSeconds(55)
        );
    }
}
