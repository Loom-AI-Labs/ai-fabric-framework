package com.ai.fabric.realapps.agenticresolver.service;

import com.ai.fabric.realapps.agenticresolver.entity.Address;
import com.ai.fabric.realapps.agenticresolver.entity.PaymentMethod;
import com.ai.fabric.realapps.agenticresolver.entity.RefundRequest;
import com.ai.fabric.realapps.agenticresolver.entity.Subscription;
import com.ai.fabric.realapps.agenticresolver.entity.SubscriptionPlan;
import com.ai.fabric.realapps.agenticresolver.entity.User;
import com.ai.fabric.realapps.agenticresolver.repository.RefundRequestRepository;
import com.ai.fabric.realapps.agenticresolver.repository.SubscriptionPlanRepository;
import com.ai.fabric.realapps.agenticresolver.repository.SubscriptionRepository;
import com.ai.fabric.realapps.agenticresolver.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountResolutionServiceTest {

    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final SubscriptionPlanRepository planRepository = mock(SubscriptionPlanRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final RefundRequestRepository refundRequestRepository = mock(RefundRequestRepository.class);
    private final BehaviorEventService behaviorEventService = new BehaviorEventService();
    private final AccountResolutionService service = new AccountResolutionService(
        subscriptionRepository,
        planRepository,
        userRepository,
        refundRequestRepository,
        behaviorEventService
    );

    @Test
    void inspectReadinessReturnsPaymentAndAddressBlockers() {
        User user = user(92L);
        Subscription subscription = activeSubscription(user.getId());
        subscription.setPaymentMethod(null);
        subscription.setBillingAddress(null);
        when(userRepository.findByUserId(92L)).thenReturn(Optional.of(user));
        when(subscriptionRepository.findByUserIdAndStatus(user.getId(), Subscription.SubscriptionStatus.ACTIVE))
            .thenReturn(Optional.of(subscription));

        AccountResolutionService.AccountReadiness readiness = service.inspectReadiness(92L);

        assertThat(readiness.canContinue()).isFalse();
        assertThat(readiness.recommendedActions()).containsExactly("update_payment_method", "update_address");
        assertThat(readiness.blockers())
            .extracting(AccountResolutionService.AccountBlocker::code)
            .containsExactly("PAYMENT_METHOD_MISSING", "BILLING_ADDRESS_MISSING");
    }

    @Test
    void accountProfileReturnsFactsWithoutReadinessDecisions() {
        User user = user(93L);
        Subscription subscription = activeSubscription(user.getId());
        subscription.setPaymentMethod(PaymentMethod.builder()
            .type(PaymentMethod.PaymentType.CARD)
            .provider("Visa")
            .last4("4093")
            .verified(true)
            .build());
        subscription.setBillingAddress(null);
        subscription.setShippingAddress(null);
        SubscriptionPlan plan = SubscriptionPlan.builder()
            .id(subscription.getPlanId())
            .name("Pro Plan")
            .tier(SubscriptionPlan.PlanTier.PRO)
            .monthlyPrice(new BigDecimal("29.00"))
            .annualPrice(new BigDecimal("290.00"))
            .maxUsers(25)
            .storageGB(100)
            .build();
        when(userRepository.findByUserId(93L)).thenReturn(Optional.of(user));
        when(subscriptionRepository.findByUserIdAndStatus(user.getId(), Subscription.SubscriptionStatus.ACTIVE))
            .thenReturn(Optional.of(subscription));
        when(planRepository.findById(subscription.getPlanId())).thenReturn(Optional.of(plan));

        AccountResolutionService.AccountProfile profile = service.accountProfile(93L);

        assertThat(profile.account().displayName()).isEqualTo("Resolver User");
        assertThat(profile.subscription().active()).isTrue();
        assertThat(profile.subscription().plan().tier()).isEqualTo("PRO");
        assertThat(profile.paymentMethod().present()).isTrue();
        assertThat(profile.paymentMethod().verified()).isTrue();
        assertThat(profile.billingAddress().present()).isFalse();
        assertThat(profile.billingAddress().validated()).isFalse();
        assertThat(profile.toString())
            .doesNotContain("canContinue")
            .doesNotContain("recommendedActions")
            .doesNotContain("AccountBlocker");
    }

    @Test
    void updatePaymentMethodClearsPaymentBlockerWhenAddressIsValid() {
        User user = user(91L);
        Subscription subscription = activeSubscription(user.getId());
        subscription.setBillingAddress(validBillingAddress());
        subscription.setPaymentMethod(null);
        when(subscriptionRepository.findById(subscription.getId())).thenReturn(Optional.of(subscription));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        AccountResolutionService.PaymentMethodResult result = service.updatePaymentMethod(
            subscription.getId(),
            PaymentMethod.PaymentType.CARD,
            "Visa",
            "tok_4242"
        );

        assertThat(result.verified()).isTrue();
        assertThat(result.last4()).isEqualTo("4242");
        assertThat(result.readiness().canContinue()).isTrue();
        assertThat(subscription.getPaymentMethod().getProvider()).isEqualTo("Visa");
        assertThat(behaviorEventService.getEventsForUser(user.getId().toString(), null, null))
            .extracting(event -> event.getEventData().get("last4"))
            .containsExactly("4242");
        verify(subscriptionRepository).save(subscription);
    }

    @Test
    void requestRefundApprovesSmallAccountCreditAndTracksEvent() {
        UUID refundId = UUID.randomUUID();
        User user = user(94L);
        Subscription subscription = activeSubscription(user.getId());
        when(subscriptionRepository.findById(subscription.getId())).thenReturn(Optional.of(subscription));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(refundRequestRepository.save(any(RefundRequest.class))).thenAnswer(invocation -> {
            RefundRequest request = invocation.getArgument(0);
            request.setId(refundId);
            request.setCreatedAt(LocalDateTime.now());
            return request;
        });

        AccountResolutionService.RefundResolutionResult result = service.requestRefund(
            subscription.getId(),
            new BigDecimal("25"),
            "Billing issue",
            RefundRequest.ResolutionType.ACCOUNT_CREDIT
        );

        assertThat(result.refundRequestId()).isEqualTo(refundId);
        assertThat(result.resolutionType()).isEqualTo("ACCOUNT_CREDIT");
        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.amount()).isEqualByComparingTo("25.00");
        assertThat(result.policyDecision()).isEqualTo("AUTO_APPROVED");
        assertThat(result.policyExplanation()).contains("Auto-approved", "$100");
        assertThat(result.autoApprovalLimit()).isEqualByComparingTo("100.00");
        assertThat(behaviorEventService.getEventsForUser(user.getId().toString(), null, null))
            .singleElement()
            .satisfies(event -> assertThat(event.getEventData())
                .containsEntry("status", "APPROVED")
                .containsEntry("policyDecision", "AUTO_APPROVED"));
    }

    @Test
    void requestRefundRoutesLargeCashRefundToReviewAndExplainsPolicy() {
        UUID refundId = UUID.randomUUID();
        User user = user(94L);
        Subscription subscription = activeSubscription(user.getId());
        when(subscriptionRepository.findById(subscription.getId())).thenReturn(Optional.of(subscription));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(refundRequestRepository.save(any(RefundRequest.class))).thenAnswer(invocation -> {
            RefundRequest request = invocation.getArgument(0);
            request.setId(refundId);
            request.setCreatedAt(LocalDateTime.now());
            return request;
        });

        AccountResolutionService.RefundResolutionResult result = service.requestRefund(
            subscription.getId(),
            new BigDecimal("75"),
            "Billing issue",
            RefundRequest.ResolutionType.REFUND
        );

        assertThat(result.refundRequestId()).isEqualTo(refundId);
        assertThat(result.resolutionType()).isEqualTo("REFUND");
        assertThat(result.status()).isEqualTo("PENDING_REVIEW");
        assertThat(result.amount()).isEqualByComparingTo("75.00");
        assertThat(result.policyDecision()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.policyExplanation()).contains("Routed to review", "$50");
        assertThat(result.autoApprovalLimit()).isEqualByComparingTo("50.00");
        assertThat(behaviorEventService.getEventsForUser(user.getId().toString(), null, null))
            .singleElement()
            .satisfies(event -> assertThat(event.getEventData())
                .containsEntry("status", "PENDING_REVIEW")
                .containsEntry("policyDecision", "REVIEW_REQUIRED"));
    }

    @Test
    void createDemoSessionClonesScenarioUsersForBrowserScopedState() {
        SubscriptionPlan plan = plan();
        when(planRepository.findByTier(SubscriptionPlan.PlanTier.PRO)).thenReturn(List.of(plan));
        when(userRepository.existsByUserId(any())).thenReturn(false);
        when(userRepository.findByUserId(any())).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
        when(subscriptionRepository.findByUserIdAndStatus(any(), eq(Subscription.SubscriptionStatus.ACTIVE)))
            .thenReturn(Optional.empty());
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(invocation -> {
            Subscription saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });

        AccountResolutionService.DemoSession session = service.createDemoSession("browser-session-1");

        assertThat(session.sessionId()).isEqualTo("browser-session-1");
        assertThat(session.scenarios())
            .extracting(AccountResolutionService.DemoResolverScenario::id)
            .containsExactly("ready-account", "missing-payment", "missing-address", "refund-request");
        assertThat(session.scenarios()).allSatisfy(scenario -> {
            assertThatCode(() -> UUID.fromString(scenario.userId())).doesNotThrowAnyException();
            assertThat(scenario.subscriptionId()).isNotNull();
            assertThat(scenario.baseUserId()).isBetween(91L, 94L);
        });
        assertThat(session.readiness()).containsKeys("ready-account", "missing-payment", "missing-address", "refund-request");
        assertThat(session.readiness().get("missing-address").numericUserId()).isGreaterThan(100L);
        assertThat(session.readiness().get("missing-address").hasVerifiedPaymentMethod()).isTrue();
        assertThat(session.readiness().get("missing-address").hasValidatedBillingAddress()).isFalse();
        assertThat(session.readiness().get("ready-account").canContinue()).isTrue();
    }

    private static User user(Long numericId) {
        return User.builder()
            .id(UUID.randomUUID())
            .userId(numericId)
            .username("resolver_user_" + numericId)
            .email("resolver" + numericId + "@example.com")
            .firstName("Resolver")
            .lastName("User")
            .build();
    }

    private static Subscription activeSubscription(UUID userId) {
        return Subscription.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .planId(UUID.randomUUID())
            .status(Subscription.SubscriptionStatus.ACTIVE)
            .billingCycle(Subscription.BillingCycle.MONTHLY)
            .startDate(LocalDateTime.now().minusDays(10))
            .endDate(LocalDateTime.now().plusDays(20))
            .lastActivityDate(LocalDateTime.now())
            .build();
    }

    private static Address validBillingAddress() {
        return Address.builder()
            .streetAddress("101 Market St")
            .city("San Francisco")
            .state("CA")
            .postalCode("94105")
            .country("USA")
            .type(Address.AddressType.BILLING)
            .isValidated(true)
            .validationScore(1.0)
            .build();
    }

    private static SubscriptionPlan plan() {
        return SubscriptionPlan.builder()
            .id(UUID.randomUUID())
            .name("Pro Plan")
            .tier(SubscriptionPlan.PlanTier.PRO)
            .monthlyPrice(new BigDecimal("29.00"))
            .annualPrice(new BigDecimal("290.00"))
            .maxUsers(25)
            .storageGB(100)
            .isActive(true)
            .build();
    }
}
