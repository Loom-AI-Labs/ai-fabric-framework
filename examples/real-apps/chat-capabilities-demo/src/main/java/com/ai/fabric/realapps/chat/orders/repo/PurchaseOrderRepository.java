package com.ai.fabric.realapps.chat.orders.repo;

import com.ai.fabric.realapps.chat.orders.domain.PurchaseOrder;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    List<PurchaseOrder> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<PurchaseOrder> findByOrderNumber(String orderNumber);

    Optional<PurchaseOrder> findFirstByUserIdAndStatusOrderByCreatedAtDesc(String userId, PurchaseOrder.Status status);

    List<PurchaseOrder> findByUserIdAndStatusOrderByCreatedAtDesc(String userId, PurchaseOrder.Status status);

    List<PurchaseOrder> findByUserIdStartingWithAndCreatedAtBefore(String userIdPrefix, LocalDateTime cutoff);
}
