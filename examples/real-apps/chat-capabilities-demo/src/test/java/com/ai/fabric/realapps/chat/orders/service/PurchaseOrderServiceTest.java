package com.ai.fabric.realapps.chat.orders.service;

import com.ai.fabric.realapps.chat.catalog.service.ProductService;
import com.ai.fabric.realapps.chat.orders.domain.PurchaseOrder;
import com.ai.fabric.realapps.chat.orders.repo.OrderItemRepository;
import com.ai.fabric.realapps.chat.orders.repo.PurchaseOrderRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PurchaseOrderServiceTest {

    private final PurchaseOrderRepository purchaseOrderRepository = mock(PurchaseOrderRepository.class);
    private final OrderItemRepository orderItemRepository = mock(OrderItemRepository.class);
    private final ProductService productService = mock(ProductService.class);
    private final PurchaseOrderService service = new PurchaseOrderService(
        purchaseOrderRepository,
        orderItemRepository,
        productService
    );

    @Test
    void resolveForUserUsesNumericIdWhenReferenceContainsOnlyDigits() {
        PurchaseOrder order = order(42L, "PO-42", "user-1");
        when(purchaseOrderRepository.findById(42L)).thenReturn(Optional.of(order));

        assertThat(service.resolveForUser("42", "user-1")).isSameAs(order);
    }

    @Test
    void resolveForUserFallsBackToOrderNumberForNonNumericReference() {
        PurchaseOrder order = order(42L, "PO-42", "user-1");
        when(purchaseOrderRepository.findByOrderNumber("PO-42")).thenReturn(Optional.of(order));

        assertThat(service.resolveForUser("PO-42", "user-1")).isSameAs(order);
    }

    @Test
    void resolveForUserRejectsMismatchedUser() {
        PurchaseOrder order = order(42L, "PO-42", "other-user");
        when(purchaseOrderRepository.findByOrderNumber("PO-42")).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.resolveForUser("PO-42", "user-1"))
            .isInstanceOf(EntityNotFoundException.class);
    }

    private static PurchaseOrder order(long id, String orderNumber, String userId) {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(id);
        order.setOrderNumber(orderNumber);
        order.setUserId(userId);
        return order;
    }
}
