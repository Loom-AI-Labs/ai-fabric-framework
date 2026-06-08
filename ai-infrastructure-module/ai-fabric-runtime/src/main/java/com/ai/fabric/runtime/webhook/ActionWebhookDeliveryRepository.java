package com.ai.fabric.runtime.webhook;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ActionWebhookDeliveryRepository extends JpaRepository<ActionWebhookDeliveryEntity, String> {

    long countByStatus(String status);

    List<ActionWebhookDeliveryEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select delivery
        from ActionWebhookDeliveryEntity delivery
        where (
            delivery.status in :readyStatuses
            and delivery.nextAttemptAt <= :now
        ) or (
            delivery.status = :inProgressStatus
            and delivery.claimedAt is not null
            and delivery.claimedAt <= :staleBefore
        )
        order by delivery.createdAt asc
        """)
    List<ActionWebhookDeliveryEntity> findDispatchableForUpdate(@Param("readyStatuses") Collection<String> readyStatuses,
                                                                @Param("inProgressStatus") String inProgressStatus,
                                                                @Param("now") Instant now,
                                                                @Param("staleBefore") Instant staleBefore,
                                                                Pageable pageable);
}
