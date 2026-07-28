package com.ai.fabric.realapps.agenticresolver.service;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import com.ai.fabric.realapps.agenticresolver.entity.Subscription;
import com.ai.fabric.realapps.agenticresolver.entity.SubscriptionPlan;
import com.ai.fabric.realapps.agenticresolver.entity.Address;
import com.ai.fabric.realapps.agenticresolver.repository.SubscriptionPlanRepository;
import com.ai.fabric.realapps.agenticresolver.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPlanRepository planRepository;
    private final BehaviorEventService behaviorEventService;
    private final AICoreService aiCoreService;

    /**
     * Subscribe to a plan
     * The subscription remains application-owned state and is not vector indexed.
     */
    @Transactional
    public Subscription subscribe(UUID userId, UUID planId, Subscription.BillingCycle billingCycle) {
        SubscriptionPlan plan = planRepository.findById(planId)
            .orElseThrow(() -> new RuntimeException("Plan not found"));

        // Check if user already has active subscription
        if (subscriptionRepository.existsByUserIdAndStatus(userId, Subscription.SubscriptionStatus.ACTIVE)) {
            throw new IllegalStateException("User already has an active subscription");
        }

        // Create subscription
        Subscription subscription = Subscription.builder()
            .userId(userId)
            .planId(planId)
            .status(Subscription.SubscriptionStatus.ACTIVE)
            .startDate(LocalDateTime.now())
            .billingCycle(billingCycle)
            .endDate(calculateEndDate(billingCycle))
            .lastActivityDate(LocalDateTime.now())
            .build();

        Subscription saved = subscriptionRepository.save(subscription);

        // Track event for behavior analysis
        behaviorEventService.trackEvent(userId, "SUBSCRIBE", Map.of(
            "planId", planId.toString(),
            "planName", plan.getName(),
            "billingCycle", billingCycle.toString()
        ));

        log.info("User {} subscribed to plan {}", userId, planId);
        return saved;
    }

    /**
     * Unsubscribe (cancel subscription)
     * Subscription state is read through governed account actions.
     */
    @Transactional
    public Subscription unsubscribe(UUID subscriptionId, String reason) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new RuntimeException("Subscription not found"));

        subscription.setStatus(Subscription.SubscriptionStatus.CANCELLED);
        subscription.setEndDate(LocalDateTime.now());
        subscription.setLastActivityDate(LocalDateTime.now());

        Subscription saved = subscriptionRepository.save(subscription);

        // Track event for behavior analysis
        behaviorEventService.trackEvent(subscription.getUserId(), "UNSUBSCRIBE", Map.of(
            "subscriptionId", subscriptionId.toString(),
            "reason", reason != null ? reason : "User requested"
        ));

        log.info("Subscription {} cancelled by user {}", subscriptionId, subscription.getUserId());
        return saved;
    }

    /**
     * Upgrade to higher tier plan
     * Plan knowledge remains indexed separately from current account state.
     */
    @Transactional
    public Subscription upgrade(UUID subscriptionId, UUID newPlanId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new RuntimeException("Subscription not found"));

        SubscriptionPlan oldPlan = planRepository.findById(subscription.getPlanId())
            .orElseThrow(() -> new RuntimeException("Current plan not found"));
        SubscriptionPlan newPlan = planRepository.findById(newPlanId)
            .orElseThrow(() -> new RuntimeException("New plan not found"));

        // Validate upgrade (new plan must be higher tier)
        if (!isValidUpgrade(oldPlan.getTier(), newPlan.getTier())) {
            throw new IllegalArgumentException("Invalid upgrade path from " + oldPlan.getTier() + " to " + newPlan.getTier());
        }

        subscription.setPlanId(newPlanId);
        subscription.setLastActivityDate(LocalDateTime.now());

        Subscription saved = subscriptionRepository.save(subscription);

        // Track event for behavior analysis
        behaviorEventService.trackEvent(subscription.getUserId(), "UPGRADE", Map.of(
            "oldPlanId", subscription.getPlanId().toString(),
            "newPlanId", newPlanId.toString(),
            "oldTier", oldPlan.getTier().toString(),
            "newTier", newPlan.getTier().toString()
        ));

        log.info("Subscription {} upgraded from {} to {}", subscriptionId, oldPlan.getTier(), newPlan.getTier());
        return saved;
    }

    /**
     * Downgrade to lower tier plan
     */
    @Transactional
    public Subscription downgrade(UUID subscriptionId, UUID newPlanId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new RuntimeException("Subscription not found"));

        SubscriptionPlan oldPlan = planRepository.findById(subscription.getPlanId())
            .orElseThrow(() -> new RuntimeException("Current plan not found"));
        SubscriptionPlan newPlan = planRepository.findById(newPlanId)
            .orElseThrow(() -> new RuntimeException("New plan not found"));

        // Validate downgrade
        if (!isValidDowngrade(oldPlan.getTier(), newPlan.getTier())) {
            throw new IllegalArgumentException("Invalid downgrade path from " + oldPlan.getTier() + " to " + newPlan.getTier());
        }

        subscription.setPlanId(newPlanId);
        subscription.setLastActivityDate(LocalDateTime.now());

        Subscription saved = subscriptionRepository.save(subscription);

        behaviorEventService.trackEvent(subscription.getUserId(), "DOWNGRADE", Map.of(
            "oldPlanId", subscription.getPlanId().toString(),
            "newPlanId", newPlanId.toString(),
            "oldTier", oldPlan.getTier().toString(),
            "newTier", newPlan.getTier().toString()
        ));

        return saved;
    }

    /**
     * Update billing or shipping address
     * Address state remains in the account system of record.
     */
    @Transactional
    public Subscription updateAddress(UUID subscriptionId, Address.AddressType addressType, Address address) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new RuntimeException("Subscription not found"));

        if (addressType == Address.AddressType.BILLING) {
            subscription.setBillingAddress(address);
        } else {
            subscription.setShippingAddress(address);
        }

        subscription.setLastActivityDate(LocalDateTime.now());

        Subscription saved = subscriptionRepository.save(subscription);

        // Track event for behavior analysis
        behaviorEventService.trackEvent(subscription.getUserId(), "UPDATE_ADDRESS", Map.of(
            "addressType", addressType.toString(),
            "isValidated", address.getIsValidated().toString()
        ));

        return saved;
    }

    /**
     * Semantic search for subscription plans
     */
    public List<SubscriptionPlan> searchPlans(String query, int limit) {
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isBlank()) {
            throw new IllegalArgumentException("Plan search query is required");
        }
        if (limit < 1) {
            throw new IllegalArgumentException(
                "Plan search limit must be positive"
            );
        }

        AISearchRequest searchRequest = AISearchRequest.builder()
            .query(normalizedQuery)
            .entityType("subscription-plan")
            .limit(limit)
            .build();
        AISearchResponse response = aiCoreService.performSearch(searchRequest);
        if (response == null
            || response.getResults() == null
            || response.getResults().isEmpty()) {
            return List.of();
        }

        return response.getResults().stream()
            .map(this::findPlanFromSearchResult)
            .flatMap(Optional::stream)
            .limit(limit)
            .toList();
    }

    private Optional<SubscriptionPlan> findPlanFromSearchResult(Map<String, Object> result) {
        return extractEntityId(result).flatMap(entityId -> {
            try {
                return planRepository.findById(UUID.fromString(entityId));
            } catch (IllegalArgumentException ex) {
                log.debug("Unable to parse plan entityId '{}' as UUID (result keys: {})", entityId, result.keySet());
                return Optional.empty();
            }
        });
    }

    private Optional<String> extractEntityId(Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return Optional.empty();
        }
        return firstPresent(result.get("entityId"), result.get("id"))
            .map(Objects::toString)
            .filter(value -> !value.isBlank());
    }

    private Optional<Object> firstPresent(Object first, Object second) {
        return Optional.ofNullable(first != null ? first : second);
    }

    /**
     * Get user's active subscription
     */
    public Optional<Subscription> getActiveSubscription(UUID userId) {
        return subscriptionRepository.findByUserIdAndStatus(userId, Subscription.SubscriptionStatus.ACTIVE);
    }

    /**
     * Check if user has active subscription
     */
    public boolean hasActiveSubscription(UUID userId) {
        return subscriptionRepository.existsByUserIdAndStatus(userId, Subscription.SubscriptionStatus.ACTIVE);
    }

    /**
     * Get subscription by ID
     */
    public Subscription findById(UUID subscriptionId) {
        return subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new RuntimeException("Subscription not found"));
    }

    /**
     * Resolve a user-facing plan reference such as "Pro" or "Enterprise Plan" to a plan id.
     */
    public UUID resolvePlanId(String planReference) {
        if (planReference == null || planReference.isBlank()) {
            throw new IllegalArgumentException("Plan name or tier is required");
        }

        String trimmed = planReference.trim();
        try {
            UUID id = UUID.fromString(trimmed);
            return planRepository.findById(id)
                .map(SubscriptionPlan::getId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found"));
        } catch (IllegalArgumentException ignored) {
            // Continue with human-readable plan matching.
        }

        String normalized = normalizePlanReference(trimmed);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Plan name or tier is required");
        }
        List<SubscriptionPlan> plans = planRepository.findByIsActiveTrue();
        if (plans == null || plans.isEmpty()) {
            plans = planRepository.findAll();
        }

        return plans.stream()
            .filter(plan -> plan != null && plan.getId() != null)
            .filter(plan -> matchesPlanReference(plan, normalized))
            .map(SubscriptionPlan::getId)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Plan not found for: " + planReference));
    }

    // Helper methods

    private boolean matchesPlanReference(SubscriptionPlan plan, String normalizedReference) {
        String name = normalizePlanReference(plan.getName());
        String tier = plan.getTier() != null ? normalizePlanReference(plan.getTier().name()) : "";
        return name.equals(normalizedReference)
            || (!name.isBlank() && name.contains(normalizedReference))
            || (!name.isBlank() && normalizedReference.contains(name))
            || tier.equals(normalizedReference);
    }

    private String normalizePlanReference(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
            .replace("plan", "")
            .replaceAll("[^a-z0-9]+", "")
            .trim();
    }

    private LocalDateTime calculateEndDate(Subscription.BillingCycle billingCycle) {
        LocalDateTime now = LocalDateTime.now();
        return billingCycle == Subscription.BillingCycle.MONTHLY
            ? now.plusMonths(1)
            : now.plusYears(1);
    }

    private boolean isValidUpgrade(SubscriptionPlan.PlanTier current, SubscriptionPlan.PlanTier target) {
        return switch (current) {
            case BASIC -> target == SubscriptionPlan.PlanTier.PRO || target == SubscriptionPlan.PlanTier.ENTERPRISE;
            case PRO -> target == SubscriptionPlan.PlanTier.ENTERPRISE;
            case ENTERPRISE -> false;
        };
    }

    private boolean isValidDowngrade(SubscriptionPlan.PlanTier current, SubscriptionPlan.PlanTier target) {
        return switch (current) {
            case ENTERPRISE -> target == SubscriptionPlan.PlanTier.PRO || target == SubscriptionPlan.PlanTier.BASIC;
            case PRO -> target == SubscriptionPlan.PlanTier.BASIC;
            case BASIC -> false;
        };
    }
}
