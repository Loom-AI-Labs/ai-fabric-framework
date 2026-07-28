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
import java.util.concurrent.ThreadLocalRandom;

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
        return scenarioDefinitions().stream()
            .map(definition -> new ResolverScenario(
                definition.id(),
                definition.baseUserId(),
                definition.title(),
                definition.description(),
                definition.suggestedPrompt()
            ))
            .toList();
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

    @Transactional(readOnly = true)
    public AccountReadiness inspectReadinessByUserId(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        return inspectReadinessForUser(user);
    }

    @Transactional(readOnly = true)
    public AccountProfile accountProfile(Long numericUserId) {
        User user = userRepository.findByUserId(numericUserId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + numericUserId));
        Subscription subscription = subscriptionRepository
            .findByUserIdAndStatus(user.getId(), Subscription.SubscriptionStatus.ACTIVE)
            .orElse(null);
        return buildAccountProfile(user, subscription);
    }

    @Transactional(readOnly = true)
    public AccountProfile accountProfile(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        Subscription subscription = subscriptionRepository
            .findByUserIdAndStatus(user.getId(), Subscription.SubscriptionStatus.ACTIVE)
            .orElse(null);
        return buildAccountProfile(user, subscription);
    }

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
            "amount", savedRefund.getAmount().toPlainString(),
            "policyDecision", policyDecision(savedRefund.getStatus()),
            "policyExplanation", refundPolicyExplanation(savedRefund.getResolutionType(), savedRefund.getStatus()),
            "autoApprovalLimit", autoApprovalLimit(savedRefund.getResolutionType()).toPlainString()
        ));

        return new RefundResolutionResult(
            savedRefund.getId(),
            savedRefund.getSubscriptionId(),
            savedRefund.getUserId(),
            savedRefund.getResolutionType().name(),
            savedRefund.getStatus().name(),
            savedRefund.getAmount(),
            savedRefund.getReason(),
            savedRefund.getCreatedAt(),
            policyDecision(savedRefund.getStatus()),
            refundPolicyExplanation(savedRefund.getResolutionType(), savedRefund.getStatus()),
            autoApprovalLimit(savedRefund.getResolutionType())
        );
    }

    @Transactional
    public Map<String, AccountReadiness> seedDemoScenarios() {
        SubscriptionPlan plan = preferredPlan();
        Map<String, AccountReadiness> seeded = new LinkedHashMap<>();
        for (DemoScenarioDefinition definition : scenarioDefinitions()) {
            seeded.put(definition.id(), buildScenario(
                definition.baseUserId(),
                definition.firstName(),
                definition.lastName(),
                plan,
                definition.includeAddress(),
                definition.includePaymentMethod(),
                definition.active(),
                definition.churnRiskScore()
            ));
        }
        return seeded;
    }

    @Transactional
    public DemoSession createDemoSession(String requestedSessionId) {
        String sessionId = normalizeDemoSessionId(requestedSessionId);
        SubscriptionPlan plan = preferredPlan();
        List<DemoResolverScenario> demoScenarios = new ArrayList<>();
        Map<String, AccountReadiness> readinessByScenario = new LinkedHashMap<>();

        for (DemoScenarioDefinition definition : scenarioDefinitions()) {
            Long sessionNumericUserId = nextSessionNumericUserId();
            AccountReadiness readiness = buildScenario(
                sessionNumericUserId,
                definition.firstName(),
                definition.lastName(),
                plan,
                definition.includeAddress(),
                definition.includePaymentMethod(),
                definition.active(),
                definition.churnRiskScore()
            );
            readinessByScenario.put(definition.id(), readiness);
            demoScenarios.add(new DemoResolverScenario(
                definition.id(),
                readiness.userId().toString(),
                readiness.subscriptionId(),
                definition.baseUserId(),
                definition.title(),
                definition.description(),
                definition.suggestedPrompt()
            ));
        }

        return new DemoSession(sessionId, demoScenarios, readinessByScenario);
    }

    private List<DemoScenarioDefinition> scenarioDefinitions() {
        return List.of(
            new DemoScenarioDefinition(
                "ready-account",
                READY_USER_ID,
                "Ava",
                "Ready",
                "Ready account",
                "Account has an active subscription, validated address, and verified payment method.",
                "Can I continue using the app and make an order?",
                true,
                true,
                true,
                0.18
            ),
            new DemoScenarioDefinition(
                "missing-payment",
                MISSING_PAYMENT_USER_ID,
                "Noah",
                "Payment",
                "Missing payment method",
                "Account has an active subscription and address, but checkout is blocked by a missing payment method.",
                "Why can't I place an order? If payment is missing, add my Visa ending 4242.",
                true,
                false,
                true,
                0.48
            ),
            new DemoScenarioDefinition(
                "missing-address",
                MISSING_ADDRESS_USER_ID,
                "Mia",
                "Address",
                "Missing billing address",
                "Account has a verified payment method, but checkout is blocked by a missing billing address.",
                "Resolve the issue blocking my account and set my billing address to 101 Market St, San Francisco, CA 94105, USA.",
                false,
                true,
                true,
                0.36
            ),
            new DemoScenarioDefinition(
                "refund-request",
                REFUND_USER_ID,
                "Ethan",
                "Refund",
                "Refund or account credit",
                "Account is usable, but the user needs a governed billing resolution.",
                "I was charged after a support incident. Please give me a $25 account credit.",
                true,
                true,
                true,
                0.64
            )
        );
    }

    private AccountReadiness inspectReadinessForUser(User user) {
        Subscription subscription = subscriptionRepository
            .findByUserIdAndStatus(user.getId(), Subscription.SubscriptionStatus.ACTIVE)
            .orElse(null);
        return buildReadiness(user, subscription);
    }

    private AccountProfile buildAccountProfile(User user, Subscription subscription) {
        SubscriptionPlan plan = subscription != null && subscription.getPlanId() != null
            ? planRepository.findById(subscription.getPlanId()).orElse(null)
            : null;

        return new AccountProfile(
            user != null
                ? new AccountHolderProfile(
                    fullName(user),
                    Boolean.TRUE.equals(user.getIsGuest()),
                    user.getLastLoginAt()
                )
                : null,
            subscription != null
                ? new SubscriptionProfile(
                    subscription.getStatus().name(),
                    subscription.getStatus() == Subscription.SubscriptionStatus.ACTIVE,
                    subscription.getBillingCycle() != null ? subscription.getBillingCycle().name() : null,
                    subscription.getEndDate(),
                    subscription.getLastActivityDate(),
                    subscription.getChurnRiskScore(),
                    planProfile(plan)
                )
                : new SubscriptionProfile(
                    "NO_ACTIVE_SUBSCRIPTION",
                    false,
                    null,
                    null,
                    null,
                    null,
                    null
                ),
            paymentMethodProfile(subscription != null ? subscription.getPaymentMethod() : null),
            addressProfile(subscription != null ? subscription.getBillingAddress() : null, "BILLING"),
            addressProfile(subscription != null ? subscription.getShippingAddress() : null, "SHIPPING")
        );
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

    private String fullName(User user) {
        String first = user.getFirstName() != null ? user.getFirstName().trim() : "";
        String last = user.getLastName() != null ? user.getLastName().trim() : "";
        return (first + " " + last).trim();
    }

    private PlanProfile planProfile(SubscriptionPlan plan) {
        if (plan == null) {
            return null;
        }
        return new PlanProfile(
            plan.getName(),
            plan.getTier() != null ? plan.getTier().name() : null,
            plan.getMonthlyPrice(),
            plan.getAnnualPrice(),
            plan.getMaxUsers(),
            plan.getStorageGB()
        );
    }

    private PaymentMethodProfile paymentMethodProfile(PaymentMethod paymentMethod) {
        if (paymentMethod == null) {
            return new PaymentMethodProfile(false, false, null, null, null);
        }
        return new PaymentMethodProfile(
            true,
            Boolean.TRUE.equals(paymentMethod.getVerified()),
            paymentMethod.getType() != null ? paymentMethod.getType().name() : null,
            paymentMethod.getProvider(),
            paymentMethod.getLast4()
        );
    }

    private AddressProfile addressProfile(Address address, String defaultType) {
        if (address == null) {
            return new AddressProfile(false, false, defaultType, null, null, null, null);
        }
        return new AddressProfile(
            true,
            Boolean.TRUE.equals(address.getIsValidated()),
            address.getType() != null ? address.getType().name() : defaultType,
            address.getCity(),
            address.getState(),
            address.getPostalCode(),
            address.getCountry()
        );
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
                .createdAt(demoUserCreatedAt(numericUserId))
                .build());
        user.setUsername("resolver_user_" + numericUserId);
        user.setEmail("resolver.user" + numericUserId + "@example.com");
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setLastLoginAt(LocalDateTime.now().minusMinutes(20));
        return userRepository.save(user);
    }

    private LocalDateTime demoUserCreatedAt(Long numericUserId) {
        return numericUserId != null && numericUserId > 100
            ? LocalDateTime.now()
            : LocalDateTime.now().minusMonths(2);
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

    private Long nextSessionNumericUserId() {
        for (int attempt = 0; attempt < 25; attempt++) {
            long candidate = ThreadLocalRandom.current().nextLong(10_000L, 999_999_999L);
            if (!userRepository.existsByUserId(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate a unique demo user id");
    }

    private String normalizeDemoSessionId(String requestedSessionId) {
        if (!StringUtils.hasText(requestedSessionId)) {
            return "account-resolver-" + UUID.randomUUID();
        }
        String trimmed = requestedSessionId.trim();
        return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 120);
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
        BigDecimal limit = autoApprovalLimit(resolutionType);
        return amount.compareTo(limit) <= 0
            ? RefundRequest.RefundStatus.APPROVED
            : RefundRequest.RefundStatus.PENDING_REVIEW;
    }

    private BigDecimal autoApprovalLimit(RefundRequest.ResolutionType resolutionType) {
        return resolutionType == RefundRequest.ResolutionType.ACCOUNT_CREDIT
            ? AUTO_APPROVE_CREDIT_LIMIT
            : AUTO_APPROVE_REFUND_LIMIT;
    }

    private String policyDecision(RefundRequest.RefundStatus status) {
        return status == RefundRequest.RefundStatus.APPROVED
            ? "AUTO_APPROVED"
            : "REVIEW_REQUIRED";
    }

    private String refundPolicyExplanation(RefundRequest.ResolutionType resolutionType, RefundRequest.RefundStatus status) {
        String subject = resolutionType == RefundRequest.ResolutionType.ACCOUNT_CREDIT
            ? "account-credit"
            : "refund";
        String limit = "$" + autoApprovalLimit(resolutionType).stripTrailingZeros().toPlainString();
        if (status == RefundRequest.RefundStatus.APPROVED) {
            return "Auto-approved under the small " + subject + " policy (" + limit + " or less).";
        }
        return "Routed to review because this " + subject + " is above the " + limit + " auto-approval limit.";
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

    public record AccountProfile(
        AccountHolderProfile account,
        SubscriptionProfile subscription,
        PaymentMethodProfile paymentMethod,
        AddressProfile billingAddress,
        AddressProfile shippingAddress
    ) { }

    public record AccountHolderProfile(
        String displayName,
        boolean guest,
        LocalDateTime lastLoginAt
    ) { }

    public record SubscriptionProfile(
        String status,
        boolean active,
        String billingCycle,
        LocalDateTime currentPeriodEndsAt,
        LocalDateTime lastActivityAt,
        Double churnRiskScore,
        PlanProfile plan
    ) { }

    public record PlanProfile(
        String name,
        String tier,
        BigDecimal monthlyPrice,
        BigDecimal annualPrice,
        Integer maxUsers,
        Integer storageGB
    ) { }

    public record PaymentMethodProfile(
        boolean present,
        boolean verified,
        String type,
        String provider,
        String last4
    ) { }

    public record AddressProfile(
        boolean present,
        boolean validated,
        String type,
        String city,
        String state,
        String postalCode,
        String country
    ) { }

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

    public record DemoSession(
        String sessionId,
        List<DemoResolverScenario> scenarios,
        Map<String, AccountReadiness> readiness
    ) {
        public DemoSession {
            scenarios = scenarios != null ? List.copyOf(scenarios) : List.of();
            readiness = readiness != null ? Map.copyOf(readiness) : Map.of();
        }
    }

    public record DemoResolverScenario(
        String id,
        String userId,
        UUID subscriptionId,
        Long baseUserId,
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
        LocalDateTime createdAt,
        String policyDecision,
        String policyExplanation,
        BigDecimal autoApprovalLimit
    ) { }

    private record DemoScenarioDefinition(
        String id,
        Long baseUserId,
        String firstName,
        String lastName,
        String title,
        String description,
        String suggestedPrompt,
        boolean includeAddress,
        boolean includePaymentMethod,
        boolean active,
        double churnRiskScore
    ) { }
}
