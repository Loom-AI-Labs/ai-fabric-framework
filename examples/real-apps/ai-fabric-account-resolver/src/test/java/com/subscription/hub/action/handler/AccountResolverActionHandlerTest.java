package com.subscription.hub.action.handler;

import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.orchestration.OrchestrationContext;
import com.subscription.hub.entity.Address;
import com.subscription.hub.entity.PaymentMethod;
import com.subscription.hub.entity.Subscription;
import com.subscription.hub.service.AccountResolutionService;
import com.subscription.hub.service.SubscriptionService;
import com.subscription.hub.service.UserService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
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
        when(accountResolutionService.updatePaymentMethod(
            eq(subscriptionId),
            eq(PaymentMethod.PaymentType.CARD),
            eq("Visa"),
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

        assertThat(handler.allowed(context)).isTrue();
        assertThat(handler.confirm("Visa", "4242")).contains("Visa").contains("4242");

        ActionResult result = handler.execute(null, PaymentMethod.PaymentType.CARD, "Visa", "4242", context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("ready to continue");
        verify(accountResolutionService).updatePaymentMethod(
            subscriptionId,
            PaymentMethod.PaymentType.CARD,
            "Visa",
            "4242"
        );
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
