package com.ai.fabric.realapps.agenticresolver.action.handler;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.orchestration.OrchestrationContext;
import com.ai.fabric.realapps.agenticresolver.entity.Address;
import com.ai.fabric.realapps.agenticresolver.entity.RefundRequest;
import com.ai.fabric.realapps.agenticresolver.entity.Subscription;
import com.ai.fabric.realapps.agenticresolver.service.AccountResolutionService;
import com.ai.fabric.realapps.agenticresolver.service.SubscriptionService;
import com.ai.fabric.realapps.agenticresolver.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class AccountResolverActionHandlerTest {

    private final AccountResolutionService accountResolutionService = mock(AccountResolutionService.class);
    private final SubscriptionService subscriptionService = mock(SubscriptionService.class);
    private final UserService userService = mock(UserService.class);

    @Test
    void paymentActionResolvesActiveSubscriptionFromUserContext() {
        UUID userUuid = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.builder()
            .id(subscriptionId)
            .userId(userUuid)
            .status(Subscription.SubscriptionStatus.ACTIVE)
            .billingCycle(Subscription.BillingCycle.MONTHLY)
            .startDate(LocalDateTime.now().minusDays(3))
            .build();
        AccountResolutionService.AccountReadiness readiness = new AccountResolutionService.AccountReadiness(
            subscriptionId,
            userUuid,
            92L,
            "ACTIVE",
            true,
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            true,
            true
        );
        AccountResolutionService.PaymentMethodResult paymentResult = new AccountResolutionService.PaymentMethodResult(
            subscriptionId,
            userUuid,
            "CARD",
            "Visa",
            "4242",
            true,
            readiness
        );
        when(userService.getUserIdFromNumeric(92L)).thenReturn(userUuid);
        when(subscriptionService.hasActiveSubscription(userUuid)).thenReturn(true);
        when(subscriptionService.getActiveSubscription(userUuid)).thenReturn(Optional.of(subscription));
        when(accountResolutionService.updatePaymentMethod(eq(subscriptionId), isNull(), isNull(), eq("4242")))
            .thenReturn(paymentResult);

        UpdatePaymentMethodActionHandler handler = new UpdatePaymentMethodActionHandler(
            accountResolutionService,
            subscriptionService,
            userService
        );
        ActionContext context = new ActionContext(OrchestrationContext.builder()
            .userId("92")
            .sessionId("resolver-test")
            .build(), null);

        assertThat(handler.allowed(context)).isTrue();
        assertThat(handler.confirm("Visa", "4242")).contains("Visa").contains("4242");

        ActionResult result = handler.execute("4242", context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("ready to continue");
        verify(accountResolutionService).updatePaymentMethod(eq(subscriptionId), isNull(), isNull(), eq("4242"));
    }

    @Test
    void paymentActionDoesNotRequireUserToProvideSubscriptionTypeOrProvider() {
        UUID userUuid = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.builder()
            .id(subscriptionId)
            .userId(userUuid)
            .status(Subscription.SubscriptionStatus.ACTIVE)
            .billingCycle(Subscription.BillingCycle.MONTHLY)
            .startDate(LocalDateTime.now().minusDays(3))
            .build();
        AccountResolutionService.AccountReadiness readiness = new AccountResolutionService.AccountReadiness(
            subscriptionId,
            userUuid,
            92L,
            "ACTIVE",
            true,
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            true,
            true
        );
        AccountResolutionService.PaymentMethodResult paymentResult = new AccountResolutionService.PaymentMethodResult(
            subscriptionId,
            userUuid,
            "CARD",
            "card",
            "4242",
            true,
            readiness
        );
        when(userService.getUserIdFromNumeric(92L)).thenReturn(userUuid);
        when(subscriptionService.getActiveSubscription(userUuid)).thenReturn(Optional.of(subscription));
        when(accountResolutionService.updatePaymentMethod(
            eq(subscriptionId),
            isNull(),
            isNull(),
            eq("4242")
        )).thenReturn(paymentResult);

        UpdatePaymentMethodActionHandler handler = new UpdatePaymentMethodActionHandler(
            accountResolutionService,
            subscriptionService,
            userService
        );
        ActionContext context = new ActionContext(OrchestrationContext.builder()
            .userId("92")
            .sessionId("resolver-test")
            .build(), null);

        ActionResult result = handler.execute("4242", context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("ready to continue");
        verify(accountResolutionService).updatePaymentMethod(eq(subscriptionId), isNull(), isNull(), eq("4242"));
    }

    @Test
    void profileActionResolvesAccountFromCurrentUserContextOnly() {
        UUID userUuid = UUID.randomUUID();
        AccountResolutionService.AccountProfile profile = new AccountResolutionService.AccountProfile(
            new AccountResolutionService.AccountHolderProfile("Resolver User", false, LocalDateTime.now()),
            new AccountResolutionService.SubscriptionProfile("ACTIVE", true, "MONTHLY", null, null, null, null),
            new AccountResolutionService.PaymentMethodProfile(false, false, null, null, null),
            new AccountResolutionService.AddressProfile(true, true, "BILLING", "San Francisco", "CA", "94105", "USA"),
            new AccountResolutionService.AddressProfile(true, true, "SHIPPING", "San Francisco", "CA", "94105", "USA")
        );
        when(accountResolutionService.accountProfile(92L)).thenReturn(profile);

        GetAccountProfileActionHandler handler = new GetAccountProfileActionHandler(
            accountResolutionService,
            userService
        );
        ActionContext context = new ActionContext(OrchestrationContext.builder()
            .userId("92")
            .sessionId("resolver-test")
            .build(), null);

        ActionResult result = handler.execute(context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("profile facts");
        assertThat(result.getData().toMap())
            .containsKey("accountProfile")
            .doesNotContainKeys("readiness", "canContinue", "blockers", "recommendedActions", "policies");
        verify(accountResolutionService).accountProfile(92L);
    }

    @Test
    void profileActionResolvesUuidUserContextOnly() {
        UUID userUuid = UUID.randomUUID();
        AccountResolutionService.AccountProfile profile = new AccountResolutionService.AccountProfile(
            new AccountResolutionService.AccountHolderProfile("Resolver User", false, LocalDateTime.now()),
            new AccountResolutionService.SubscriptionProfile("ACTIVE", true, "MONTHLY", null, null, null, null),
            new AccountResolutionService.PaymentMethodProfile(
                true,
                true,
                "CARD",
                "Visa",
                "4242"
            ),
            new AccountResolutionService.AddressProfile(false, false, "BILLING", null, null, null, null),
            new AccountResolutionService.AddressProfile(false, false, "SHIPPING", null, null, null, null)
        );
        when(accountResolutionService.accountProfile(userUuid)).thenReturn(profile);

        GetAccountProfileActionHandler handler = new GetAccountProfileActionHandler(
            accountResolutionService,
            userService
        );
        ActionContext context = new ActionContext(OrchestrationContext.builder()
            .userId(userUuid.toString())
            .sessionId("resolver-test")
            .build(), null);

        ActionResult result = handler.execute(context);

        assertThat(result.isSuccess()).isTrue();
        verify(accountResolutionService).accountProfile(userUuid);
    }

    @Test
    void profileActionDoesNotReturnBackendReadinessDecisions() {
        AccountResolutionService.AccountProfile profile = new AccountResolutionService.AccountProfile(
            new AccountResolutionService.AccountHolderProfile("Resolver User", false, LocalDateTime.now()),
            new AccountResolutionService.SubscriptionProfile(
                "ACTIVE",
                true,
                "MONTHLY",
                null,
                null,
                null,
                null
            ),
            new AccountResolutionService.PaymentMethodProfile(
                false,
                false,
                null,
                null,
                null
            ),
            new AccountResolutionService.AddressProfile(false, false, "BILLING", null, null, null, null),
            new AccountResolutionService.AddressProfile(false, false, "SHIPPING", null, null, null, null)
        );
        when(accountResolutionService.accountProfile(92L)).thenReturn(profile);

        GetAccountProfileActionHandler handler = new GetAccountProfileActionHandler(
            accountResolutionService,
            userService
        );
        ActionContext context = new ActionContext(OrchestrationContext.builder()
            .userId("92")
            .sessionId("resolver-test")
            .build(), null);

        ActionResult result = handler.execute(context);

        assertThat(result.isSuccess()).isTrue();
        Map<String, Object> data = result.getData().toMap();
        assertThat(data).containsOnlyKeys("accountProfile");
        assertThat(data.toString())
            .doesNotContain("canContinue")
            .doesNotContain("recommendedActions")
            .doesNotContain("AccountBlocker");
    }

    @Test
    void profileActionFactsAreCompactAndDecisionFree() {
        AccountResolutionService.AccountProfile profile = new AccountResolutionService.AccountProfile(
            new AccountResolutionService.AccountHolderProfile("Resolver User", false, LocalDateTime.now()),
            new AccountResolutionService.SubscriptionProfile(
                "ACTIVE",
                true,
                "MONTHLY",
                null,
                null,
                null,
                new AccountResolutionService.PlanProfile("Pro Plan", "PRO", null, null, 25, 100)
            ),
            new AccountResolutionService.PaymentMethodProfile(true, true, "CARD", "Visa", "4093"),
            new AccountResolutionService.AddressProfile(false, false, "BILLING", null, null, null, null),
            new AccountResolutionService.AddressProfile(true, true, "SHIPPING", "San Francisco", "CA", "94105", "USA")
        );
        when(accountResolutionService.accountProfile(93L)).thenReturn(profile);

        GetAccountProfileActionHandler handler = new GetAccountProfileActionHandler(
            accountResolutionService,
            userService
        );
        ActionContext context = new ActionContext(OrchestrationContext.builder()
            .userId("93")
            .sessionId("resolver-test")
            .build(), null);

        Map<String, Object> facts = handler.facts(ActionResult.builder().success(true).build(), context);

        assertThat(facts)
            .containsEntry("subscriptionActive", true)
            .containsEntry("paymentMethodVerified", true)
            .containsEntry("billingAddressPresent", false)
            .containsEntry("billingAddressValidated", false);
        assertThat(facts.toString())
            .doesNotContain("canContinue")
            .doesNotContain("recommendedActions")
            .doesNotContain("blockers");
    }

    @Test
    void refundActionMessageIncludesPolicyDecisionFromBackendResult() {
        UUID userUuid = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        Subscription subscription = Subscription.builder()
            .id(subscriptionId)
            .userId(userUuid)
            .status(Subscription.SubscriptionStatus.ACTIVE)
            .billingCycle(Subscription.BillingCycle.MONTHLY)
            .startDate(LocalDateTime.now().minusDays(3))
            .build();
        AccountResolutionService.RefundResolutionResult refundResult = new AccountResolutionService.RefundResolutionResult(
            refundId,
            subscriptionId,
            userUuid,
            "REFUND",
            "PENDING_REVIEW",
            new BigDecimal("75.00"),
            "Support incident",
            LocalDateTime.now(),
            "REVIEW_REQUIRED",
            "Routed to review because this refund is above the $50 auto-approval limit.",
            new BigDecimal("50.00")
        );
        when(userService.getUserIdFromNumeric(94L)).thenReturn(userUuid);
        when(subscriptionService.hasActiveSubscription(userUuid)).thenReturn(true);
        when(subscriptionService.getActiveSubscription(userUuid)).thenReturn(Optional.of(subscription));
        when(accountResolutionService.requestRefund(
            eq(subscriptionId),
            eq(new BigDecimal("75")),
            eq("Support incident"),
            eq(RefundRequest.ResolutionType.REFUND)
        )).thenReturn(refundResult);

        RequestRefundActionHandler handler = new RequestRefundActionHandler(
            accountResolutionService,
            subscriptionService,
            userService
        );
        ActionContext context = new ActionContext(OrchestrationContext.builder()
            .userId("94")
            .sessionId("resolver-test")
            .build(), null);

        ActionResult result = handler.execute(
            new BigDecimal("75"),
            "Support incident",
            RefundRequest.ResolutionType.REFUND,
            context
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage())
            .contains("PENDING_REVIEW")
            .contains("Routed to review");
        Map<String, Object> data = result.getData().toMap();
        assertThat(data)
            .containsEntry("policyDecision", "REVIEW_REQUIRED")
            .containsEntry("policyExplanation", "Routed to review because this refund is above the $50 auto-approval limit.");
    }

    @Test
    void billingAssessmentActionExposesStableAuthoritativeFacts() {
        AccountResolutionService.BillingResolutionAssessment assessment =
            new AccountResolutionService.BillingResolutionAssessment(
                "REFUND",
                new BigDecimal("75.00"),
                "REVIEW_REQUIRED",
                "PENDING_REVIEW",
                new BigDecimal("50.00"),
                "Routed to review because this refund is above the limit."
            );
        when(accountResolutionService.assessBillingResolution(
            new BigDecimal("75.00"),
            RefundRequest.ResolutionType.REFUND
        )).thenReturn(assessment);
        AssessBillingResolutionActionHandler handler =
            new AssessBillingResolutionActionHandler(
                accountResolutionService
            );
        ActionContext context = new ActionContext(
            OrchestrationContext.builder()
                .userId("94")
                .sessionId("resolver-test")
                .build(),
            null
        );

        ActionResult result = handler.execute(
            new BigDecimal("75.00"),
            RefundRequest.ResolutionType.REFUND,
            context
        );
        Map<String, Object> facts = handler.facts(result, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().toMap())
            .containsEntry("resolutionType", "REFUND")
            .containsEntry("amount", new BigDecimal("75.00"))
            .containsEntry("decision", "REVIEW_REQUIRED")
            .containsEntry("expectedStatus", "PENDING_REVIEW")
            .containsEntry("automaticLimit", new BigDecimal("50.00"));
        assertThat(facts)
            .containsEntry("factSource", "billing_resolution_policy")
            .containsEntry("decision", "REVIEW_REQUIRED")
            .containsEntry("expectedStatus", "PENDING_REVIEW");
    }

    @Test
    void billingAssessmentActionAcceptsPositiveSubUnitAmountsThroughRegistryBinding() {
        BigDecimal amount = new BigDecimal("0.50");
        AccountResolutionService.BillingResolutionAssessment assessment =
            new AccountResolutionService.BillingResolutionAssessment(
                "REFUND",
                amount,
                "AUTO_APPROVED",
                "APPROVED",
                new BigDecimal("50.00"),
                "Automatically approved under the refund limit."
            );
        when(accountResolutionService.assessBillingResolution(
            amount,
            RefundRequest.ResolutionType.REFUND
        )).thenReturn(assessment);

        try (AnnotationConfigApplicationContext context =
                 new AnnotationConfigApplicationContext()) {
            context.registerBean(
                AccountResolutionService.class,
                () -> accountResolutionService
            );
            context.register(AIActionRegistry.class);
            context.register(AssessBillingResolutionActionHandler.class);
            context.refresh();

            AIActionHandler handler = context
                .getBean(AIActionRegistry.class)
                .findHandler("assess_billing_resolution")
                .orElseThrow();
            ActionContext actionContext = new ActionContext(
                OrchestrationContext.builder()
                    .userId("94")
                    .sessionId("resolver-test")
                    .build(),
                null
            );

            ActionResult result = handler.executeAction(
                Map.of(
                    "amount", "0.50",
                    "resolutionType", "REFUND"
                ),
                actionContext
            );

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getData().toMap())
                .containsEntry("amount", amount)
                .containsEntry("decision", "AUTO_APPROVED");
            assertThat(handler.getActionMetadata()
                .getParameterSchemas()
                .get("amount")
                .getMin())
                .isZero();
        }
    }

    @Test
    void billingAssessmentFactsRejectIncompleteActionPayloads() {
        AssessBillingResolutionActionHandler handler =
            new AssessBillingResolutionActionHandler(
                accountResolutionService
            );
        ActionResult incomplete = ActionResult.builder()
            .success(true)
            .data(ai.fabric.intent.action.ActionResultContracts.object(
                Map.of("amount", new BigDecimal("12.00"))
            ))
            .build();

        assertThatThrownBy(() -> handler.facts(incomplete, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("resolutionType");
    }

    @Test
    void actionMetadataExposesOnlyUserSuppliedParameters() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AccountResolutionService.class, () -> accountResolutionService);
            context.registerBean(SubscriptionService.class, () -> subscriptionService);
            context.registerBean(UserService.class, () -> userService);
            context.register(AIActionRegistry.class);
            context.register(GetAccountProfileActionHandler.class);
            context.register(AssessBillingResolutionActionHandler.class);
            context.register(UpdatePaymentMethodActionHandler.class);
            context.register(UpdateAddressActionHandler.class);
            context.register(RequestRefundActionHandler.class);
            context.register(CancelSubscriptionActionHandler.class);
            context.register(UpgradeSubscriptionActionHandler.class);
            context.register(DowngradeSubscriptionActionHandler.class);
            context.register(SubscribeActionHandler.class);
            context.refresh();

            AIActionRegistry registry = context.getBean(AIActionRegistry.class);

            assertThat(registry.getAllMetadata())
                .extracting(AIActionMetaData::getName)
                .containsExactlyInAnyOrder(
                    "get_account_profile",
                    "assess_billing_resolution",
                    "update_payment_method",
                    "update_address",
                    "request_refund",
                    "cancel_subscription",
                    "upgrade_subscription",
                    "downgrade_subscription",
                    "subscribe"
                );
            assertThat(registry.getAllMetadata()).allSatisfy(action ->
                assertThat(action.getParameters().keySet())
                    .doesNotContain("userId", "subscriptionId", "tenantId", "accountId", "planId", "newPlanId")
            );

            assertThat(registry.findMetadata("inspect_account_readiness")).isEmpty();
            AIActionMetaData profile = registry.findMetadata("get_account_profile").orElseThrow();
            assertThat(profile.getParameters()).isEmpty();
            assertThat(profile.getParameterSchemas()).isEmpty();
            assertThat(profile.getRequiredParameters()).isEmpty();

            AIActionMetaData assessment = registry
                .findMetadata("assess_billing_resolution")
                .orElseThrow();
            assertThat(assessment.getParameters())
                .containsOnlyKeys("amount", "resolutionType");
            assertThat(assessment.getRequiredParameters())
                .containsExactlyInAnyOrder("amount", "resolutionType");
            assertThat(assessment.getParameterSchemas()
                .get("resolutionType")
                .getAllowedValues())
                .containsExactly("REFUND", "ACCOUNT_CREDIT");

            AIActionMetaData payment = registry.findMetadata("update_payment_method").orElseThrow();
            assertThat(payment.getParameters()).containsOnlyKeys("last4");
            assertThat(payment.getParameterSchemas()).containsOnlyKeys("last4");
            assertThat(payment.getRequiredParameters()).containsExactly("last4");

            AIActionMetaData address = registry.findMetadata("update_address").orElseThrow();
            assertThat(address.getParameters())
                .containsOnlyKeys("addressType", "streetAddress", "city", "state", "postalCode", "country");
            assertThat(address.getParameterSchemas())
                .containsOnlyKeys("addressType", "streetAddress", "city", "state", "postalCode", "country");
            assertThat(address.getRequiredParameters())
                .containsExactlyInAnyOrder("streetAddress", "city", "state", "postalCode", "country");
            assertThat(address.getParameterSchemas().get("addressType")
                .getAllowedValues())
                .containsExactly("BILLING", "SHIPPING");
            assertThat(address.getParameterSchemas().get("streetAddress")
                .getPattern())
                .isEqualTo("(?s).{1,200}");
            assertThat(address.getParameterSchemas().get("postalCode")
                .getPattern())
                .isEqualTo("(?s).{1,32}");

            AIActionMetaData refund = registry.findMetadata("request_refund").orElseThrow();
            assertThat(refund.getParameters()).containsOnlyKeys("amount", "reason", "resolutionType");
            assertThat(refund.getRequiredParameters()).containsExactly("amount");

            AIActionMetaData cancel = registry.findMetadata("cancel_subscription").orElseThrow();
            assertThat(cancel.getParameters()).containsOnlyKeys("reason");
            assertThat(cancel.getRequiredParameters()).isEmpty();

            AIActionMetaData upgrade = registry.findMetadata("upgrade_subscription").orElseThrow();
            assertThat(upgrade.getParameters()).containsOnlyKeys("newPlanName");
            assertThat(upgrade.getRequiredParameters()).containsExactly("newPlanName");

            AIActionMetaData downgrade = registry.findMetadata("downgrade_subscription").orElseThrow();
            assertThat(downgrade.getParameters()).containsOnlyKeys("newPlanName");
            assertThat(downgrade.getRequiredParameters()).containsExactly("newPlanName");

            AIActionMetaData subscribe = registry.findMetadata("subscribe").orElseThrow();
            assertThat(subscribe.getParameters()).containsOnlyKeys("planName", "billingCycle");
            assertThat(subscribe.getRequiredParameters()).containsExactly("planName");
        }
    }

    @Test
    void addressActionResolvesActiveSubscriptionFromUserContext() {
        UUID userUuid = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.builder()
            .id(subscriptionId)
            .userId(userUuid)
            .status(Subscription.SubscriptionStatus.ACTIVE)
            .billingCycle(Subscription.BillingCycle.MONTHLY)
            .startDate(LocalDateTime.now().minusDays(3))
            .build();
        when(userService.getUserIdFromNumeric(93L)).thenReturn(userUuid);
        when(subscriptionService.hasActiveSubscription(userUuid)).thenReturn(true);
        when(subscriptionService.getActiveSubscription(userUuid)).thenReturn(Optional.of(subscription));
        when(subscriptionService.updateAddress(eq(subscriptionId), eq(Address.AddressType.BILLING), isA(Address.class)))
            .thenReturn(subscription);

        UpdateAddressActionHandler handler = new UpdateAddressActionHandler(subscriptionService, userService);
        ActionContext context = new ActionContext(OrchestrationContext.builder()
            .userId("93")
            .sessionId("resolver-test")
            .build(), null);

        assertThat(handler.allowed(context)).isTrue();

        ActionResult result = handler.execute(
            "BILLING",
            "101 Market St",
            "San Francisco",
            "CA",
            "94105",
            "USA",
            context
        );

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<Address> addressCaptor =
            ArgumentCaptor.forClass(Address.class);
        verify(subscriptionService).updateAddress(
            eq(subscriptionId),
            eq(Address.AddressType.BILLING),
            addressCaptor.capture()
        );
        assertThat(addressCaptor.getValue()).satisfies(address -> {
            assertThat(address.getStreetAddress()).isEqualTo("101 Market St");
            assertThat(address.getPostalCode()).isEqualTo("94105");
            assertThat(address.getIsValidated()).isTrue();
            assertThat(address.getValidationScore()).isEqualTo(1.0);
        });
    }

    @Test
    void unexpectedAddressWriteFailurePropagatesForUnknownOutcomeClassification() {
        UUID userUuid = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.builder()
            .id(subscriptionId)
            .userId(userUuid)
            .status(Subscription.SubscriptionStatus.ACTIVE)
            .billingCycle(Subscription.BillingCycle.MONTHLY)
            .startDate(LocalDateTime.now().minusDays(3))
            .build();
        when(userService.getUserIdFromNumeric(93L)).thenReturn(userUuid);
        when(subscriptionService.getActiveSubscription(userUuid))
            .thenReturn(Optional.of(subscription));
        when(subscriptionService.updateAddress(
            eq(subscriptionId),
            eq(Address.AddressType.BILLING),
            isA(Address.class)
        )).thenThrow(new IllegalStateException(
            "database write unavailable for internal-subscription-id"
        ));
        UpdateAddressActionHandler handler =
            new UpdateAddressActionHandler(
                subscriptionService,
                userService
            );
        ActionContext context = new ActionContext(
            OrchestrationContext.builder()
                .userId("93")
                .sessionId("resolver-test")
                .build(),
            null
        );

        assertThatThrownBy(() -> handler.execute(
                "BILLING",
                "101 Market St",
                "San Francisco",
                "CA",
                "94105",
                "USA",
                context
            ))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelActionResolvesActiveSubscriptionFromUserContext() {
        UUID userUuid = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription active = Subscription.builder()
            .id(subscriptionId)
            .userId(userUuid)
            .status(Subscription.SubscriptionStatus.ACTIVE)
            .billingCycle(Subscription.BillingCycle.MONTHLY)
            .startDate(LocalDateTime.now().minusDays(3))
            .build();
        Subscription cancelled = Subscription.builder()
            .id(subscriptionId)
            .userId(userUuid)
            .status(Subscription.SubscriptionStatus.CANCELLED)
            .billingCycle(Subscription.BillingCycle.MONTHLY)
            .startDate(active.getStartDate())
            .endDate(LocalDateTime.now())
            .build();
        when(userService.getUserIdFromNumeric(94L)).thenReturn(userUuid);
        when(subscriptionService.hasActiveSubscription(userUuid)).thenReturn(true);
        when(subscriptionService.getActiveSubscription(userUuid)).thenReturn(Optional.of(active));
        when(subscriptionService.unsubscribe(eq(subscriptionId), eq("No longer needed"))).thenReturn(cancelled);

        CancelSubscriptionActionHandler handler = new CancelSubscriptionActionHandler(subscriptionService, userService);
        ActionContext context = new ActionContext(OrchestrationContext.builder()
            .userId("94")
            .sessionId("resolver-test")
            .build(), null);

        assertThat(handler.allowed(context)).isTrue();
        assertThat(handler.confirm()).contains("current subscription");

        ActionResult result = handler.execute("No longer needed", context);

        assertThat(result.isSuccess()).isTrue();
        verify(subscriptionService).unsubscribe(eq(subscriptionId), eq("No longer needed"));
    }

    @Test
    void upgradeActionResolvesActiveSubscriptionAndPlanNameFromContext() {
        UUID userUuid = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        Subscription active = Subscription.builder()
            .id(subscriptionId)
            .userId(userUuid)
            .status(Subscription.SubscriptionStatus.ACTIVE)
            .billingCycle(Subscription.BillingCycle.MONTHLY)
            .startDate(LocalDateTime.now().minusDays(3))
            .build();
        Subscription upgraded = Subscription.builder()
            .id(subscriptionId)
            .userId(userUuid)
            .planId(planId)
            .status(Subscription.SubscriptionStatus.ACTIVE)
            .billingCycle(Subscription.BillingCycle.MONTHLY)
            .startDate(active.getStartDate())
            .build();
        when(userService.getUserIdFromNumeric(95L)).thenReturn(userUuid);
        when(subscriptionService.hasActiveSubscription(userUuid)).thenReturn(true);
        when(subscriptionService.getActiveSubscription(userUuid)).thenReturn(Optional.of(active));
        when(subscriptionService.resolvePlanId("Enterprise")).thenReturn(planId);
        when(subscriptionService.upgrade(eq(subscriptionId), eq(planId))).thenReturn(upgraded);

        UpgradeSubscriptionActionHandler handler = new UpgradeSubscriptionActionHandler(subscriptionService, userService);
        ActionContext context = new ActionContext(OrchestrationContext.builder()
            .userId("95")
            .sessionId("resolver-test")
            .build(), null);

        assertThat(handler.allowed(context)).isTrue();
        assertThat(handler.confirm("Enterprise")).contains("Enterprise");

        ActionResult result = handler.execute("Enterprise", context);

        assertThat(result.isSuccess()).isTrue();
        verify(subscriptionService).upgrade(eq(subscriptionId), eq(planId));
    }

    @Test
    void downgradeActionResolvesActiveSubscriptionAndPlanNameFromContext() {
        UUID userUuid = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        Subscription active = Subscription.builder()
            .id(subscriptionId)
            .userId(userUuid)
            .status(Subscription.SubscriptionStatus.ACTIVE)
            .billingCycle(Subscription.BillingCycle.MONTHLY)
            .startDate(LocalDateTime.now().minusDays(3))
            .build();
        Subscription downgraded = Subscription.builder()
            .id(subscriptionId)
            .userId(userUuid)
            .planId(planId)
            .status(Subscription.SubscriptionStatus.ACTIVE)
            .billingCycle(Subscription.BillingCycle.MONTHLY)
            .startDate(active.getStartDate())
            .build();
        when(userService.getUserIdFromNumeric(96L)).thenReturn(userUuid);
        when(subscriptionService.hasActiveSubscription(userUuid)).thenReturn(true);
        when(subscriptionService.getActiveSubscription(userUuid)).thenReturn(Optional.of(active));
        when(subscriptionService.resolvePlanId("Basic")).thenReturn(planId);
        when(subscriptionService.downgrade(eq(subscriptionId), eq(planId))).thenReturn(downgraded);

        DowngradeSubscriptionActionHandler handler = new DowngradeSubscriptionActionHandler(subscriptionService, userService);
        ActionContext context = new ActionContext(OrchestrationContext.builder()
            .userId("96")
            .sessionId("resolver-test")
            .build(), null);

        assertThat(handler.allowed(context)).isTrue();
        assertThat(handler.confirm("Basic")).contains("Basic");

        ActionResult result = handler.execute("Basic", context);

        assertThat(result.isSuccess()).isTrue();
        verify(subscriptionService).downgrade(eq(subscriptionId), eq(planId));
    }

    @Test
    void subscribeActionResolvesCurrentUserAndPlanName() {
        UUID userUuid = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        Subscription subscription = Subscription.builder()
            .id(subscriptionId)
            .userId(userUuid)
            .planId(planId)
            .status(Subscription.SubscriptionStatus.ACTIVE)
            .billingCycle(Subscription.BillingCycle.MONTHLY)
            .startDate(LocalDateTime.now())
            .build();
        when(userService.getUserIdFromNumeric(97L)).thenReturn(userUuid);
        when(subscriptionService.hasActiveSubscription(userUuid)).thenReturn(false);
        when(subscriptionService.resolvePlanId("Pro")).thenReturn(planId);
        when(subscriptionService.subscribe(eq(userUuid), eq(planId), eq(Subscription.BillingCycle.MONTHLY)))
            .thenReturn(subscription);

        SubscribeActionHandler handler = new SubscribeActionHandler(subscriptionService, userService);
        ActionContext context = new ActionContext(OrchestrationContext.builder()
            .userId("97")
            .sessionId("resolver-test")
            .build(), null);

        assertThat(handler.allowed(context)).isTrue();
        assertThat(handler.confirm("Pro", "MONTHLY")).contains("Pro");

        ActionResult result = handler.execute("Pro", "MONTHLY", context);

        assertThat(result.isSuccess()).isTrue();
        verify(subscriptionService).subscribe(eq(userUuid), eq(planId), eq(Subscription.BillingCycle.MONTHLY));
    }
}
