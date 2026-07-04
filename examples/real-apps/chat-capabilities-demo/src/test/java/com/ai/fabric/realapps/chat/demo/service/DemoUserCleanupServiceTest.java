package com.ai.fabric.realapps.chat.demo.service;

import ai.fabric.chat.domain.ChatSession;
import com.ai.fabric.realapps.chat.cart.domain.Cart;
import com.ai.fabric.realapps.chat.cart.repo.CartItemRepository;
import com.ai.fabric.realapps.chat.cart.repo.CartRepository;
import com.ai.fabric.realapps.chat.orders.domain.PurchaseOrder;
import com.ai.fabric.realapps.chat.orders.repo.OrderItemRepository;
import com.ai.fabric.realapps.chat.orders.repo.PurchaseOrderRepository;
import com.ai.fabric.realapps.chat.payments.repo.PaymentRepository;
import com.ai.fabric.realapps.chat.returns.repo.ReturnRequestRepository;
import com.ai.fabric.realapps.chat.shipping.repo.ShipmentRepository;
import com.ai.fabric.realapps.chat.support.domain.SupportTicket;
import com.ai.fabric.realapps.chat.support.repo.SupportTicketRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoUserCleanupServiceTest {

    private final CartRepository cartRepository = mock(CartRepository.class);
    private final CartItemRepository cartItemRepository = mock(CartItemRepository.class);
    private final PurchaseOrderRepository purchaseOrderRepository = mock(PurchaseOrderRepository.class);
    private final OrderItemRepository orderItemRepository = mock(OrderItemRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final ShipmentRepository shipmentRepository = mock(ShipmentRepository.class);
    private final ReturnRequestRepository returnRequestRepository = mock(ReturnRequestRepository.class);
    private final SupportTicketRepository supportTicketRepository = mock(SupportTicketRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);

    private final DemoUserCleanupService cleanupService = new DemoUserCleanupService(
        cartRepository,
        cartItemRepository,
        purchaseOrderRepository,
        orderItemRepository,
        paymentRepository,
        shipmentRepository,
        returnRequestRepository,
        supportTicketRepository,
        entityManager
    );

    @Test
    @SuppressWarnings("unchecked")
    void cleanupDeletesOnlyAgedBrowserScopedDemoWritesAndProtectsSeedFixtures() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 7, 4, 12, 0);
        Cart browserCart = cart(11L, "shopping-demo-user-browser", cutoff.minusHours(2));
        Cart seedCart = cart(12L, "shopping-demo-user-seed-01", cutoff.minusHours(2));
        PurchaseOrder browserOrder = order(21L, "shopping-demo-user-browser", cutoff.minusHours(2));
        PurchaseOrder seedOrder = order(22L, "shopping-demo-user-seed-01", cutoff.minusHours(2));
        SupportTicket browserTicket = ticket(31L, "shopping-demo-user-browser", cutoff.minusHours(2));
        SupportTicket seedTicket = ticket(32L, "shopping-demo-user-seed-01", cutoff.minusHours(2));
        ChatSession browserSession = ChatSession.builder()
            .id("chat-browser")
            .ownerId("shopping-demo-user-browser")
            .lastInteractionAt(cutoff.minusHours(2))
            .createdAt(cutoff.minusHours(3))
            .build();
        ChatSession seedSession = ChatSession.builder()
            .id("chat-seed")
            .ownerId("shopping-demo-user-seed-01")
            .lastInteractionAt(cutoff.minusHours(2))
            .createdAt(cutoff.minusHours(3))
            .build();
        TypedQuery<ChatSession> chatQuery = mock(TypedQuery.class);

        when(cartRepository.findByUserIdStartingWithAndUpdatedAtBefore("shopping-demo-user-", cutoff))
            .thenReturn(List.of(browserCart, seedCart));
        when(cartItemRepository.deleteByCart_IdIn(List.of(11L))).thenReturn(2L);
        when(purchaseOrderRepository.findByUserIdStartingWithAndCreatedAtBefore("shopping-demo-user-", cutoff))
            .thenReturn(List.of(browserOrder, seedOrder));
        when(returnRequestRepository.deleteByOrder_IdIn(List.of(21L))).thenReturn(1L);
        when(paymentRepository.deleteByOrder_IdIn(List.of(21L))).thenReturn(1L);
        when(shipmentRepository.deleteByOrder_IdIn(List.of(21L))).thenReturn(1L);
        when(orderItemRepository.deleteByOrder_IdIn(List.of(21L))).thenReturn(3L);
        when(supportTicketRepository.findByUserIdStartingWithAndUpdatedAtBefore("shopping-demo-user-", cutoff))
            .thenReturn(List.of(browserTicket, seedTicket));
        when(entityManager.createQuery(anyString(), eq(ChatSession.class))).thenReturn(chatQuery);
        when(chatQuery.setParameter(anyString(), any())).thenReturn(chatQuery);
        when(chatQuery.getResultList()).thenReturn(List.of(browserSession, seedSession));

        DemoUserCleanupService.CleanupResult result = cleanupService.cleanupBefore(cutoff);

        assertThat(result.getDeleted()).containsEntry("carts", 1L);
        assertThat(result.getDeleted()).containsEntry("cartItems", 2L);
        assertThat(result.getDeleted()).containsEntry("orders", 1L);
        assertThat(result.getDeleted()).containsEntry("orderItems", 3L);
        assertThat(result.getDeleted()).containsEntry("payments", 1L);
        assertThat(result.getDeleted()).containsEntry("shipments", 1L);
        assertThat(result.getDeleted()).containsEntry("returnRequests", 1L);
        assertThat(result.getDeleted()).containsEntry("supportTickets", 1L);
        assertThat(result.getDeleted()).containsEntry("chatSessions", 1L);

        ArgumentCaptor<List<Cart>> cartsCaptor = ArgumentCaptor.forClass(List.class);
        verify(cartRepository).deleteAllInBatch(cartsCaptor.capture());
        assertThat(cartsCaptor.getValue()).containsExactly(browserCart);

        ArgumentCaptor<List<PurchaseOrder>> ordersCaptor = ArgumentCaptor.forClass(List.class);
        verify(purchaseOrderRepository).deleteAllInBatch(ordersCaptor.capture());
        assertThat(ordersCaptor.getValue()).containsExactly(browserOrder);

        ArgumentCaptor<List<SupportTicket>> ticketsCaptor = ArgumentCaptor.forClass(List.class);
        verify(supportTicketRepository).deleteAllInBatch(ticketsCaptor.capture());
        assertThat(ticketsCaptor.getValue()).containsExactly(browserTicket);

        verify(entityManager).remove(browserSession);
        verify(entityManager, never()).remove(seedSession);
    }

    private Cart cart(Long id, String userId, LocalDateTime updatedAt) {
        Cart cart = new Cart();
        cart.setId(id);
        cart.setUserId(userId);
        cart.setUpdatedAt(updatedAt);
        return cart;
    }

    private PurchaseOrder order(Long id, String userId, LocalDateTime createdAt) {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(id);
        order.setUserId(userId);
        order.setCreatedAt(createdAt);
        return order;
    }

    private SupportTicket ticket(Long id, String userId, LocalDateTime updatedAt) {
        SupportTicket ticket = new SupportTicket();
        ticket.setId(id);
        ticket.setUserId(userId);
        ticket.setUpdatedAt(updatedAt);
        return ticket;
    }
}
