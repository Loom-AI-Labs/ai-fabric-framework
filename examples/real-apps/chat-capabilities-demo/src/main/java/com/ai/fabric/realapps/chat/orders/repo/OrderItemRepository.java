package com.ai.fabric.realapps.chat.orders.repo;

import com.ai.fabric.realapps.chat.orders.domain.OrderItem;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderIdOrderByIdAsc(Long orderId);

    long deleteByOrder_IdIn(Collection<Long> orderIds);
}
