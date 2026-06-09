package com.subscription.hub.repository;

import com.subscription.hub.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    List<Subscription> findByUserId(UUID userId);

    Optional<Subscription> findByUserIdAndStatus(UUID userId, Subscription.SubscriptionStatus status);

    List<Subscription> findByStatus(Subscription.SubscriptionStatus status);

    boolean existsByUserIdAndStatus(UUID userId, Subscription.SubscriptionStatus status);
}
