package com.ai.fabric.realapps.chat.orders.service;

import com.ai.fabric.realapps.chat.orders.domain.OrderItem;
import com.ai.fabric.realapps.chat.orders.repo.OrderItemRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderItemService {

    private final OrderItemRepository orderItemRepository;

    @Transactional(readOnly = true)
    public List<OrderItem> listForOrder(long orderId) {
        return orderItemRepository.findByOrderIdOrderByIdAsc(orderId);
    }

    @Transactional(readOnly = true)
    public OrderItem get(long id) {
        return orderItemRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("OrderItem not found: " + id));
    }

    @Transactional
    public OrderItem delete(long id) {
        OrderItem item = get(id);
        orderItemRepository.delete(item);
        return item;
    }
}
