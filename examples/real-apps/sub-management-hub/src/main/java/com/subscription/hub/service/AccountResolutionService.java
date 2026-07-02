package com.subscription.hub.service;

import ai.fabric.annotation.AIProcess;
import ai.fabric.indexing.api.IndexingStrategy;
import com.subscription.hub.entity.Address;
import com.subscription.hub.entity.PaymentMethod;
import com.subscription.hub.entity.RefundRequest;
import com.subscription.hub.entity.Subscription;
import com.subscription.hub.entity.SubscriptionPlan;
import com.subscription.hub.entity.User;
import com.subscription.hub.repository.RefundRequestRepository;
import com.subscription.hub.repository.SubscriptionPlanRepository;
import com.subscription.hub.repository.SubscriptionRepository;
import com.subscription.hub.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountResolutionService {

    private static final Long READY_USER_ID = 91L;
    private static final Long MISSING_PAYMENT_USER_ID = 92L;
    private static final Long MISSING_ADDRESS_USER_ID = 93L;
    private static final Long REFUND_USER_ID = 94L;
    private static final BigDecimal AUTO_APPROVE_CREDIT_LIMIT = new BigDecimal("100.00");
    private static final BigDecimal AUTO_APPROVE_REFUND_LIMIT = new BigDecimal("50.00");

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPlanRepository planRepository;
    private final UserRepository userRepository;
    private final RefundRequestRepository refundRequestRepository;
    private final BehaviorEventService behaviorEventService;

    public List<ResolutionPolicy> policies() {
        return List.of(
            new ResolutionPolicy(
                "ACTIVE_ACCOUNT_REQUIRED",
                "Active subscription required",
                "The account must have an active subscription before product ordering or app usage can continue.",
                "subscribe",
                true
            ),
            new ResolutionPolicy(
                "PAYMENT_METHOD_REQUIRED",
                "Verified payment method required",
                "A missing or unverified payment method blocks ordering and paid feature usage until the user confirms a replacement method.",
                "update_payment_method",
                true
            ),
            new ResolutionPolicy(
                "BILLING_ADDRESS_REQUIRED",
                "Validated billing address required",
                "A missing or unvalidated billing address blocks ordering until the address is supplied and confirmed.",
                "update_address",
                true
            ),
            new ResolutionPolicy(
                "REFUND_OR_CREDIT_AVAILABLE",
                "Refund or credit available",
                "Small refunds and account credits can be resolved immediately; larger cash refunds are captured for review.",
                "request_refund",
                true
            )
        );
    }

    public List<ResolverScenario> scenarios() {
        return List.of(
            new ResolverScenario(
                "ready-account",
                READY_USER_ID,
                "Ready account",
                "Account has an active subscription, validated address, and verified payment method.",
                "Can I continue using the app and make an order?"
            ),
            new ResolverScenario(
                "missing-payment",
                MISSING_PAYMENT_USER_ID,
                "Missing payment method",
                "Account has an active subscription and address, but checkout is blocked by a missing payment method.",
                "Why can't I place an order? If payment is missing, add my Visa ending 4242."
            ),
            new ResolverScenario(
                "missing-address",
                MISSING_ADDRESS_USER_ID,
                "Missing billing address",
                "Account has a verified payment method, but checkout is blocked by a missing billing address.",
                "Resolve the issue blocking my account and set my billing address to 101 Market St, San Francisco, CA 94105, USA."
            ),
            new ResolverScenario(
                "refund-request",
                REFUND_USER_ID,
                "Refund or account credit",
                "Account is usable, but the user needs a governed billing resolution.",
                "I was charged after a support incident. Please give me a $25 account credit."
            )
        );
    }

    @Transactional(readOnly = true)
    public AccountReadiness inspectReadiness(Long numericUserId) {
        User user = userRepository.findByUserId(numericUserId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + numericUserId));
        return inspectReadinessForUser(user);
    }

    @Transactional(readOnly = true)
    public AccountReadiness inspectReadiness(UUID subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + subscriptionId));
        User user = userRepository.findById(subscription.getUserId()).orElse(null);
        return buildReadiness(user, subscription);
    }

    @AIProcess(
        entityType = "subscription",
        processType = "update",
        indexingStrategy = IndexingStrategy.SYNC
    )
    @Transactional
    public PaymentMethodResult updatePaymentMethod(UUID subscriptionId,
                                                   PaymentMethod.PaymentType type,
                                                   String provider,
                                                   String last4) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + subscriptionId));
        PaymentMethod paymentMethod = PaymentMethod.builder()
            .type(type != null ? type : PaymentMethod.PaymentType.CARD)
            .provider(StringUtils.hasText(provider) ? provider.trim() : "card")
            .last4(normalizeLast4(last4))
            .verified(true)
            .build();

        subscription.setPaymentMethod(paymentMethod);
        subscription.setLastActivityDate(LocalDateTime.now());
        Subscription saved = subscriptionRepository.save(subscription);
        behaviorEventService.trackEvent(saved.getUserId(), "UPDATE_PAYMENT_METHOD", Map.of(
            "subscriptionId", saved.getId().toString(),
            "paymentType", paymentMethod.getType().name(),
            "provider", paymentMethod.getProvider(),
            "last4", paymentMethod.getLast4()
        ));

        AccountReadiness readiness = buildReadiness(
            userRepository.findById(saved.getUserId()).orElse(null),
            saved
        );
        return new PaymentMethodResult(
            saved.getId(),
            saved.getUserId(),
            paymentMethod.getType().name(),
            paymentMethod.getProvider(),
            paymentMethod.getLast4(),
            true,
            readiness
        );
    }

    @AIProcess(
        entityType = "subscription",
        processType = "update",
        indexingStrategy = IndexingStrategy.SYNC
    )
    @Transactional
    public RefundResolutionResult requestRefund(UUID subscriptionId,
                                                BigDecimal amount,
                                                String reason,
                                                RefundRequest.ResolutionType resolutionType) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + subscriptionId));
        BigDecimal normalizedAmount = normalizeAmount(amount);
        RefundRequest.ResolutionType effectiveType =
            resolutionType != null ? resolutionType : RefundRequest.ResolutionType.ACCOUNT_CREDIT;
        RefundRequest.RefundStatus status = refundStatus(effectiveType, normalizedAmount);
        RefundRequest request = RefundRequest.builder()
            .subscriptionId(subscription.getId())
            .userId(subscription.getUserId())
            .resolutionType(effectiveType)
            .status(status)
            .amount(normalizedAmount)
            .reason(StringUtils.hasText(reason) ? reason.trim() : "Billing issue reported by user")
            .build();

        RefundRequest savedRefund = refundRequestRepository.save(request);
        subscription.setLastActivityDate(LocalDateTime.now());
        subscriptionRepository.save(subscription);
        behaviorEventService.trackEvent(subscription.getUserId(), "REQUEST_REFUND", Map.of(
            "subscriptionId", subscription.getId().toString(),
            "refundRequestId", savedRefund.getId().toString(),
            "resolutionType", savedRefund.getResolutionType().name(),
            "status", savedRefund.getStatus().name(),
            "amount", savedRefund.getAmount().toPlainString()
        ));

        return new RefundResolutionResult(
            savedRefund.getId(),
            savedRefund.getSubscriptionId(),
            savedRefund.getUserId(),
            savedRefund.getResolutionType().name(),
            savedRefund.getStatus().name(),
            savedRefund.getAmount(),
            savedRefund.getReason(),
            savedRefund.getCreatedAt()
        );
    }

    @Transactional
    public Map<String, AccountReadiness> seedDemoScenarios() {
        SubscriptionPlan plan = preferredPlan();
        Map<String, AccountReadiness> seeded = new LinkedHashMap<>();
        seeded.put("ready-account", buildScenario(
            READY_USER_ID,
            "Ava",
            "Ready",
            plan,
            true,
            true,
            true,
            0.18
        ));
        seeded.put("missing-payment", buildScenario(
            MISSING_PAYMENT_USER_ID,
            "Noah",
            "Payment",
            plan,
            true,
            false,
            true,
            0.48
        ));
        seeded.put("missing-address", buildScenario(
            MISSING_ADDRESS_USER_ID,
            "Mia",
            "Address",
            plan,
            false,
            true,
            true,
            0.36
        ));
        seeded.put("refund-request", buildScenario(
            REFUND_USER_ID,
            "Ethan",
            "Refund",
            plan,
            true,
            true,
            true,
            0.64
        ));
        return seeded;
    }

    private AccountReadiness inspectReadinessForUser(User user) {
        Subscription subscription = subscriptionRepository
            .findByUserIdAndStatus(user.getId(), Subscription.SubscriptionStatus.ACTIVE)
            .orElse(null);
        return buildReadiness(user, subscription);
    }

    private AccountReadiness buildReadiness(User user, Subscription subscription) {
        List<AccountBlocker> blockers = new ArrayList<>();
        List<String> recommendedActions = new ArrayList<>();

        if (subscription == null || subscription.getStatus() != Subscription.SubscriptionStatus.ACTIVE) {
            blockers.add(new AccountBlocker(
                "ACTIVE_SUBSCRIPTION_MISSING",
                "The user does not have an active subscription.",
                "subscribe",
                true
            ));
            recommendedActions.add("subscribe");
        } else {
            PaymentMethod paymentMethod = subscription.getPaymentMethod();
            if (paymentMethod == null || !Boolean.TRUE.equals(paymentMethod.getVerified())) {
                blockers.add(new AccountBlocker(
                    "PAYMENT_METHOD_MISSING",
                    "A verified payment method is required before the user can place an order or continue paid usage.",
                    "update_payment_method",
                    true
                ));
                recommendedActions.add("update_payment_method");
            }
            if (!isUsableAddress(subscription.getBillingAddress())) {
                blockers.add(new AccountBlocker(
                    "BILLING_ADDRESS_MISSING",
                    "A validated billing address is required before the user can place an order.",
                    "update_address",
                    true
                ));
                recommendedActions.add("update_address");
            }
        }

        boolean canContinue = blockers.isEmpty();
        return new AccountReadiness(
            subscription != null ? subscription.getId() : null,
            user != null ? user.getId() : null,
            user != null ? user.getUserId() : null,
            subscription != null ? subscription.getStatus().name() : "NO_ACTIVE_SUBSCRIPTION",
            canContinue,
            blockers,
            policies(),
            recommendedActions,
            subscription != null && subscription.getPaymentMethod() != null,
            subscription != null && isUsableAddress(subscription.getBillingAddress())
        );
    }

    private boolean isUsableAddress(Address address) {
        return address != null && Boolean.TRUE.equals(address.getIsValidated());
    }

    private AccountReadiness buildScenario(Long numericUserId,
                                           String firstName,
                                           String lastName,
                                           SubscriptionPlan plan,
                                           boolean includeAddress,
                                           boolean includePaymentMethod,
                                           boolean active,
                                           double churnRiskScore) {
        User user = upsertUser(numericUserId, firstName, lastName);
        Subscription subscription = subscriptionRepository
            .findByUserIdAndStatus(user.getId(), Subscription.SubscriptionStatus.ACTIVE)
            .orElseGet(() -> Subscription.builder()
                .userId(user.getId())
                .status(Subscription.SubscriptionStatus.ACTIVE)
                .startDate(LocalDateTime.now().minusDays(18))
                .endDate(LocalDateTime.now().plusMonths(1))
                .billingCycle(Subscription.BillingCycle.MONTHLY)
                .build());

        subscription.setPlanId(plan.getId());
        subscription.setStatus(active ? Subscription.SubscriptionStatus.ACTIVE : Subscription.SubscriptionStatus.PAST_DUE);
        subscription.setStartDate(subscription.getStartDate() != null ? subscription.getStartDate() : LocalDateTime.now().minusDays(18));
        subscription.setEndDate(LocalDateTime.now().plusMonths(1));
        subscription.setBillingCycle(Subscription.BillingCycle.MONTHLY);
        subscription.setLastActivityDate(LocalDateTime.now().minusHours(2));
        subscription.setChurnRiskScore(churnRiskScore);
        subscription.setBillingAddress(includeAddress ? demoAddress(Address.AddressType.BILLING, numericUserId) : null);
        subscription.setShippingAddress(includeAddress ? demoAddress(Address.AddressType.SHIPPING, numericUserId) : null);
        subscription.setPaymentMethod(includePaymentMethod ? demoPaymentMethod(numericUserId) : null);

        Subscription saved = subscriptionRepository.save(subscription);
        return buildReadiness(user, saved);
    }

    private User upsertUser(Long numericUserId, String firstName, String lastName) {
        User user = userRepository.findByUserId(numericUserId)
            .orElseGet(() -> User.builder()
                .userId(numericUserId)
                .isGuest(false)
                .createdAt(LocalDateTime.now().minusMonths(2))
                .build());
        user.setUsername("resolver_user_" + numericUserId);
        user.setEmail("resolver.user" + numericUserId + "@example.com");
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setLastLoginAt(LocalDateTime.now().minusMinutes(20));
        return userRepository.save(user);
    }

    private SubscriptionPlan preferredPlan() {
        Optional<SubscriptionPlan> pro = planRepository.findByTier(SubscriptionPlan.PlanTier.PRO).stream()
            .filter(plan -> Boolean.TRUE.equals(plan.getIsActive()))
            .findFirst();
        return pro.or(() -> planRepository.findByIsActiveTrue().stream().findFirst())
            .orElseThrow(() -> new IllegalStateException("No active subscription plan available"));
    }

    private Address demoAddress(Address.AddressType type, Long numericUserId) {
        return Address.builder()
            .streetAddress(numericUserId + " Market Street")
            .city("San Francisco")
            .state("CA")
            .postalCode("94105")
            .country("USA")
            .type(type)
            .isValidated(true)
            .validationScore(1.0)
            .build();
    }

    private PaymentMethod demoPaymentMethod(Long numericUserId) {
        String last4 = String.format("%04d", (4000 + numericUserId) % 10000);
        return PaymentMethod.builder()
            .type(PaymentMethod.PaymentType.CARD)
            .provider("Visa")
            .last4(last4)
            .verified(true)
            .build();
    }

    private String normalizeLast4(String last4) {
        if (!StringUtils.hasText(last4)) {
            throw new IllegalArgumentException("last4 is required");
        }
        String digits = last4.replaceAll("\\D", "");
        if (digits.length() < 4) {
            throw new IllegalArgumentException("last4 must contain at least four digits");
        }
        return digits.substring(digits.length() - 4);
    }

    private BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        return amount.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private RefundRequest.RefundStatus refundStatus(RefundRequest.ResolutionType resolutionType, BigDecimal amount) {
        BigDecimal limit = resolutionType == RefundRequest.ResolutionType.ACCOUNT_CREDIT
            ? AUTO_APPROVE_CREDIT_LIMIT
            : AUTO_APPROVE_REFUND_LIMIT;
        return amount.compareTo(limit) <= 0
            ? RefundRequest.RefundStatus.APPROVED
            : RefundRequest.RefundStatus.PENDING_REVIEW;
    }

    public record AccountReadiness(
        UUID subscriptionId,
        UUID userId,
        Long numericUserId,
        String subscriptionStatus,
        boolean canContinue,
        List<AccountBlocker> blockers,
        List<ResolutionPolicy> policies,
        List<String> recommendedActions,
        boolean hasVerifiedPaymentMethod,
        boolean hasValidatedBillingAddress
    ) {
        public AccountReadiness {
            blockers = blockers != null ? List.copyOf(blockers) : List.of();
            policies = policies != null ? List.copyOf(policies) : List.of();
            recommendedActions = recommendedActions != null
                ? recommendedActions.stream().distinct().toList()
                : List.of();
        }
    }

    public record AccountBlocker(
        String code,
        String message,
        String resolutionAction,
        boolean confirmationRequired
    ) { }

    public record ResolutionPolicy(
        String code,
        String title,
        String description,
        String actionName,
        boolean confirmationRequired
    ) { }

    public record ResolverScenario(
        String id,
        Long userId,
        String title,
        String description,
        String suggestedPrompt
    ) { }

    public record PaymentMethodResult(
        UUID subscriptionId,
        UUID userId,
        String paymentType,
        String provider,
        String last4,
        boolean verified,
        AccountReadiness readiness
    ) { }

    public record RefundResolutionResult(
        UUID refundRequestId,
        UUID subscriptionId,
        UUID userId,
        String resolutionType,
        String status,
        BigDecimal amount,
        String reason,
        LocalDateTime createdAt
    ) { }
}
