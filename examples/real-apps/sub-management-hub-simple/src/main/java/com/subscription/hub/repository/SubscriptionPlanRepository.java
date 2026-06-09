package com.subscription.hub.repository;

import com.subscription.hub.entity.SubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, UUID> {

    List<SubscriptionPlan> findByIsActiveTrue();

    List<SubscriptionPlan> findByTier(SubscriptionPlan.PlanTier tier);

    List<SubscriptionPlan> findByMonthlyPriceLessThanEqual(java.math.BigDecimal maxPrice);
}
