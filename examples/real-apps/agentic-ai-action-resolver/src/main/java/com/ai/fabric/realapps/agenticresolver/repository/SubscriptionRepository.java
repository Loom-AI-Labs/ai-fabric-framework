package com.ai.fabric.realapps.agenticresolver.repository;

import com.ai.fabric.realapps.agenticresolver.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    List<Subscription> findByUserId(UUID userId);

    List<Subscription> findByUserIdIn(Collection<UUID> userIds);

    Optional<Subscription> findByUserIdAndStatus(UUID userId, Subscription.SubscriptionStatus status);

    List<Subscription> findByStatus(Subscription.SubscriptionStatus status);

    long deleteByUserIdIn(Collection<UUID> userIds);

    boolean existsByUserIdAndStatus(UUID userId, Subscription.SubscriptionStatus status);
}
