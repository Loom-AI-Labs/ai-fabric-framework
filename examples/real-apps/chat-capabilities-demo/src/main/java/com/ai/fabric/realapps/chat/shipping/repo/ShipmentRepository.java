package com.ai.fabric.realapps.chat.shipping.repo;

import com.ai.fabric.realapps.chat.shipping.domain.Shipment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    List<Shipment> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<Shipment> findFirstByOrderIdOrderByCreatedAtDesc(Long orderId);
}
