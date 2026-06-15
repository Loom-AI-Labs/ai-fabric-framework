package com.subscription.hub.service;

import ai.fabric.annotation.AIProcess;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.core.AICoreService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import com.subscription.hub.entity.Subscription;
import com.subscription.hub.entity.SubscriptionPlan;
import com.subscription.hub.entity.Address;
import com.subscription.hub.repository.SubscriptionPlanRepository;
import com.subscription.hub.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPlanRepository planRepository;
    private final BehaviorEventService behaviorEventService;

    @Autowired(required = false)
    private AICoreService aiCoreService;

    /**
     * Subscribe to a plan
     * @AIProcess ensures plan is indexed for search
     */
    @AIProcess(
        entityType = "subscription",
        processType = "create",
        indexingStrategy = IndexingStrategy.SYNC
    )
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

        // @AIProcess ensures subscription is indexed
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
     * @AIProcess ensures subscription status is updated in index
     */
    @AIProcess(
        entityType = "subscription",
        processType = "update",
        indexingStrategy = IndexingStrategy.SYNC
    )
    @Transactional
    public Subscription unsubscribe(UUID subscriptionId, String reason) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new RuntimeException("Subscription not found"));

        subscription.setStatus(Subscription.SubscriptionStatus.CANCELLED);
        subscription.setEndDate(LocalDateTime.now());
        subscription.setLastActivityDate(LocalDateTime.now());

        // @AIProcess ensures status update is synced
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
     * @AIProcess ensures subscription is updated in index
     */
    @AIProcess(
        entityType = "subscription",
        processType = "update",
        indexingStrategy = IndexingStrategy.SYNC
    )
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

        // @AIProcess ensures upgrade is synced
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
    @AIProcess(
        entityType = "subscription",
        processType = "update",
        indexingStrategy = IndexingStrategy.SYNC
    )
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
     * @AIProcess ensures address update is synced
     */
    @AIProcess(
        entityType = "subscription",
        processType = "update",
        indexingStrategy = IndexingStrategy.SYNC
    )
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

        // @AIProcess ensures address update is synced
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

        List<SubscriptionPlan> fallback = planRepository.findAll().stream()
            .filter(plan -> matchesPlan(plan, normalizedQuery))
            .limit(limit)
            .collect(Collectors.toList());

        if (aiCoreService == null) {
            log.warn("AICoreService not available, falling back to basic search");
            return fallback;
        }

        try {
            AISearchRequest searchRequest = AISearchRequest.builder()
                .query(normalizedQuery)
                .entityType("subscription-plan")
                .limit(limit)
                .build();

            AISearchResponse response = aiCoreService.performSearch(searchRequest);
            if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
                return fallback;
            }

            // Convert search results to SubscriptionPlan entities
            return response.getResults().stream()
                .map(result -> {
                    String entityId = extractEntityId(result);
                    if (entityId == null) {
                        return null;
                    }
                    try {
                        return planRepository.findById(UUID.fromString(entityId)).orElse(null);
                    } catch (IllegalArgumentException ex) {
                        log.debug("Unable to parse plan entityId '{}' as UUID (result keys: {})", entityId, result.keySet());
                        return null;
                    }
                })
                .filter(plan -> plan != null)
                .limit(limit)
                .collect(Collectors.toList());
        } catch (Exception ex) {
            log.warn("AI plan search failed; falling back to basic search", ex);
            return fallback;
        }
    }

    private boolean matchesPlan(SubscriptionPlan plan, String query) {
        if (plan == null) {
            return false;
        }
        if (query == null || query.isBlank()) {
            return true;
        }
        String lower = query.toLowerCase();
        return containsIgnoreCase(plan.getName(), lower) || containsIgnoreCase(plan.getDescription(), lower);
    }

    private boolean containsIgnoreCase(String value, String lowerNeedle) {
        if (value == null) {
            return false;
        }
        return value.toLowerCase().contains(lowerNeedle);
    }

    private String extractEntityId(Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return null;
        }
        Object id = firstNonNull(result.get("entityId"), result.get("id"));
        return id != null ? Objects.toString(id, null) : null;
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
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

    // Helper methods

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
