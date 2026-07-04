package com.subscription.hub.repository;

import com.subscription.hub.entity.RefundRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface RefundRequestRepository extends JpaRepository<RefundRequest, UUID> {

    List<RefundRequest> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId);

    long deleteByUserIdIn(Collection<UUID> userIds);
}
