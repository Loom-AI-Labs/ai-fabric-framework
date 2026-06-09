package com.ai.fabric.realapps.chat.orders.web;

import com.ai.fabric.realapps.chat.orders.domain.PurchaseOrder;
import com.ai.fabric.realapps.chat.orders.domain.OrderItem;
import com.ai.fabric.realapps.chat.orders.service.PurchaseOrderService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    @GetMapping
    public List<PurchaseOrder> list(@RequestParam("userId") String userId,
                                    @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return purchaseOrderService.listForUser(userId, limit);
    }

    @GetMapping("/{id}")
    public PurchaseOrder get(@PathVariable long id, @RequestParam("userId") String userId) {
        return purchaseOrderService.getForUser(id, userId);
    }

    @GetMapping("/{id}/items")
    public List<OrderItem> listItems(@PathVariable long id, @RequestParam("userId") String userId) {
        return purchaseOrderService.listItemsForUser(id, userId);
    }
}
