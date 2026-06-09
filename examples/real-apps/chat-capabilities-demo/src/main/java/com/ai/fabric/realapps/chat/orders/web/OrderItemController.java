package com.ai.fabric.realapps.chat.orders.web;

import com.ai.fabric.realapps.chat.orders.domain.OrderItem;
import com.ai.fabric.realapps.chat.orders.service.OrderItemService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/order-items")
@RequiredArgsConstructor
public class OrderItemController {

    private final OrderItemService orderItemService;

    @GetMapping
    public List<OrderItem> listForOrder(@RequestParam("orderId") long orderId) {
        return orderItemService.listForOrder(orderId);
    }

    @GetMapping("/{id}")
    public OrderItem get(@PathVariable long id) {
        return orderItemService.get(id);
    }

    @DeleteMapping("/{id}")
    public OrderItem delete(@PathVariable long id) {
        return orderItemService.delete(id);
    }
}
