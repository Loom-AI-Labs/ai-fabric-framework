package com.ai.fabric.realapps.chat.shipping.web;

import com.ai.fabric.realapps.chat.shipping.domain.Shipment;
import com.ai.fabric.realapps.chat.shipping.service.ShipmentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shipments")
@RequiredArgsConstructor
public class ShipmentController {

    private final ShipmentService shipmentService;

    @GetMapping
    public List<Shipment> list(@RequestParam("userId") String userId,
                               @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return shipmentService.listForUser(userId, limit);
    }

    @GetMapping("/{id}")
    public Shipment get(@PathVariable long id) {
        return shipmentService.get(id);
    }

    @GetMapping("/by-order/{orderId}")
    public ResponseEntity<Shipment> latestForOrder(@PathVariable long orderId) {
        return shipmentService.findLatestForOrder(orderId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public Shipment delete(@PathVariable long id) {
        return shipmentService.delete(id);
    }
}
