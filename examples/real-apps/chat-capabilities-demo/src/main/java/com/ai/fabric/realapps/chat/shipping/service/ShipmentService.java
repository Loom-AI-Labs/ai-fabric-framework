package com.ai.fabric.realapps.chat.shipping.service;

import com.ai.fabric.realapps.chat.shipping.domain.Shipment;
import com.ai.fabric.realapps.chat.shipping.repo.ShipmentRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ShipmentService {

    private final ShipmentRepository shipmentRepository;

    @Transactional(readOnly = true)
    public Shipment get(long id) {
        return shipmentRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Shipment not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<Shipment> listForUser(String userId, int limit) {
        if (!StringUtils.hasText(userId)) {
            return List.of();
        }
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 200);
        return shipmentRepository.findByUserIdOrderByCreatedAtDesc(userId.trim()).stream()
            .limit(effectiveLimit)
            .toList();
    }

    @Transactional(readOnly = true)
    public Optional<Shipment> findLatestForOrder(long orderId) {
        return shipmentRepository.findFirstByOrderIdOrderByCreatedAtDesc(orderId);
    }

    @Transactional
    public Shipment delete(long id) {
        Shipment shipment = get(id);
        shipmentRepository.delete(shipment);
        return shipment;
    }
}
