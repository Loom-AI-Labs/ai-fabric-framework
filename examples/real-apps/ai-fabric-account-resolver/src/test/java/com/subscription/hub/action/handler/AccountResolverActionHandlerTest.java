package com.subscription.hub.action.handler;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.orchestration.OrchestrationContext;
import com.subscription.hub.entity.Address;
import com.subscription.hub.entity.RefundRequest;
import com.subscription.hub.entity.Subscription;
import com.subscription.hub.service.AccountResolutionService;
import com.subscription.hub.service.SubscriptionService;
import com.subscription.hub.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void readinessActionResolvesAccountFromCurrentUserContextOnly() {
        UUID userUuid = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        AccountResolutionService.AccountReadiness readiness = new AccountResolutionService.AccountReadiness(
            subscriptionId,
            userUuid,
            92L,
            "ACTIVE",
            false,
            java.util.List.of(new AccountResolutionService.AccountBlocker(
                "PAYMENT_METHOD_MISSING",
                "A verified payment method is required",
                "update_payment_method",
                true
            )),
            java.util.List.of(),
            java.util.List.of("update_payment_method"),
            false,
            true
        );
        when(accountResolutionService.inspectReadiness(92L)).thenReturn(readiness);

        InspectAccountReadinessActionHandler handler = new InspectAccountReadinessActionHandler(
            accountResolutionService,
            subscriptionService,
            userService
        );
        ActionContext context = new ActionContext(OrchestrationContext.builder()
            .userId("92")
            .sessionId("resolver-test")
            .build(), null);

        ActionResult result = handler.execute(context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("blockers");
        verify(accountResolutionService).inspectReadiness(92L);
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
            null,
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
    void actionMetadataExposesOnlyUserSuppliedParameters() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AccountResolutionService.class, () -> accountResolutionService);
            context.registerBean(SubscriptionService.class, () -> subscriptionService);
            context.registerBean(UserService.class, () -> userService);
            context.register(AIActionRegistry.class);
            context.register(InspectAccountReadinessActionHandler.class);
            context.register(UpdatePaymentMethodActionHandler.class);
            context.refresh();

            AIActionRegistry registry = context.getBean(AIActionRegistry.class);

            AIActionMetaData readiness = registry.findMetadata("inspect_account_readiness").orElseThrow();
            assertThat(readiness.getParameters()).isEmpty();
            assertThat(readiness.getParameterSchemas()).isEmpty();
            assertThat(readiness.getRequiredParameters()).isEmpty();

            AIActionMetaData payment = registry.findMetadata("update_payment_method").orElseThrow();
            assertThat(payment.getParameters()).containsOnlyKeys("last4");
            assertThat(payment.getParameterSchemas()).containsOnlyKeys("last4");
            assertThat(payment.getRequiredParameters()).containsExactly("last4");
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
            null,
            "BILLING",
            "101 Market St",
            "San Francisco",
            "CA",
            "94105",
            "USA",
            context
        );

        assertThat(result.isSuccess()).isTrue();
        verify(subscriptionService).updateAddress(eq(subscriptionId), eq(Address.AddressType.BILLING), isA(Address.class));
    }
}
