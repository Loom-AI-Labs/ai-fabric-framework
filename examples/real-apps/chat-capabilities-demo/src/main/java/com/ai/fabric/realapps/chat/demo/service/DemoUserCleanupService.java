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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class DemoUserCleanupService {

    public static final String DEFAULT_DEMO_USER_PREFIX = "shopping-demo-user-";
    public static final String DEFAULT_PROTECTED_DEMO_USER_PREFIX = "shopping-demo-user-seed-";

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final ShipmentRepository shipmentRepository;
    private final ReturnRequestRepository returnRequestRepository;
    private final SupportTicketRepository supportTicketRepository;
    private final EntityManager entityManager;

    @Value("${app.demo.cleanup.enabled:true}")
    private boolean cleanupEnabled;

    @Value("${app.demo.cleanup.ttl:PT24H}")
    private Duration cleanupTtl;

    @Value("${app.demo.cleanup.user-id-prefix:" + DEFAULT_DEMO_USER_PREFIX + "}")
    private String demoUserPrefix;

    @Value("${app.demo.cleanup.protected-user-id-prefix:" + DEFAULT_PROTECTED_DEMO_USER_PREFIX + "}")
    private String protectedDemoUserPrefix;

    @Scheduled(cron = "${app.demo.cleanup.cron:0 17 * * * *}")
    public void scheduledCleanup() {
        if (!cleanupEnabled) {
            return;
        }
        Duration ttl = cleanupTtl != null && !cleanupTtl.isNegative() && !cleanupTtl.isZero()
            ? cleanupTtl
            : Duration.ofHours(24);
        CleanupResult result = cleanupBefore(LocalDateTime.now().minus(ttl));
        if (result.deletedCount() > 0) {
            log.info("Cleaned old AI Shopping demo user data: {}", result);
        }
    }

    @Transactional
    public CleanupResult cleanupBefore(LocalDateTime cutoff) {
        if (cutoff == null) {
            throw new IllegalArgumentException("cutoff is required");
        }

        String prefix = StringUtils.hasText(demoUserPrefix) ? demoUserPrefix.trim() : DEFAULT_DEMO_USER_PREFIX;
        String protectedPrefix = StringUtils.hasText(protectedDemoUserPrefix)
            ? protectedDemoUserPrefix.trim()
            : DEFAULT_PROTECTED_DEMO_USER_PREFIX;

        Map<String, Long> deleted = new LinkedHashMap<>();
        deleted.putAll(cleanupCarts(prefix, protectedPrefix, cutoff));
        deleted.putAll(cleanupOrders(prefix, protectedPrefix, cutoff));
        deleted.put("supportTickets", cleanupSupportTickets(prefix, protectedPrefix, cutoff));
        deleted.put("chatSessions", cleanupChatSessions(prefix, protectedPrefix, cutoff));

        return CleanupResult.builder()
            .success(true)
            .cutoff(cutoff)
            .demoUserPrefix(prefix)
            .protectedDemoUserPrefix(protectedPrefix)
            .deleted(deleted)
            .build();
    }

    private Map<String, Long> cleanupCarts(String prefix, String protectedPrefix, LocalDateTime cutoff) {
        List<Cart> carts = cartRepository.findByUserIdStartingWithAndUpdatedAtBefore(prefix, cutoff).stream()
            .filter(cart -> cart != null && isCleanupUser(cart.getUserId(), prefix, protectedPrefix))
            .toList();
        List<Long> cartIds = carts.stream()
            .map(Cart::getId)
            .filter(Objects::nonNull)
            .toList();

        long deletedItems = cartIds.isEmpty() ? 0L : cartItemRepository.deleteByCart_IdIn(cartIds);
        if (!carts.isEmpty()) {
            cartRepository.deleteAllInBatch(carts);
        }

        Map<String, Long> deleted = new LinkedHashMap<>();
        deleted.put("cartItems", deletedItems);
        deleted.put("carts", (long) carts.size());
        return deleted;
    }

    private Map<String, Long> cleanupOrders(String prefix, String protectedPrefix, LocalDateTime cutoff) {
        List<PurchaseOrder> orders = purchaseOrderRepository.findByUserIdStartingWithAndCreatedAtBefore(prefix, cutoff).stream()
            .filter(order -> order != null && isCleanupUser(order.getUserId(), prefix, protectedPrefix))
            .toList();
        List<Long> orderIds = orders.stream()
            .map(PurchaseOrder::getId)
            .filter(Objects::nonNull)
            .toList();

        Map<String, Long> deleted = new LinkedHashMap<>();
        deleted.put("returnRequests", orderIds.isEmpty() ? 0L : returnRequestRepository.deleteByOrder_IdIn(orderIds));
        deleted.put("payments", orderIds.isEmpty() ? 0L : paymentRepository.deleteByOrder_IdIn(orderIds));
        deleted.put("shipments", orderIds.isEmpty() ? 0L : shipmentRepository.deleteByOrder_IdIn(orderIds));
        deleted.put("orderItems", orderIds.isEmpty() ? 0L : orderItemRepository.deleteByOrder_IdIn(orderIds));
        if (!orders.isEmpty()) {
            purchaseOrderRepository.deleteAllInBatch(orders);
        }
        deleted.put("orders", (long) orders.size());
        return deleted;
    }

    private long cleanupSupportTickets(String prefix, String protectedPrefix, LocalDateTime cutoff) {
        List<SupportTicket> tickets = supportTicketRepository.findByUserIdStartingWithAndUpdatedAtBefore(prefix, cutoff).stream()
            .filter(ticket -> ticket != null && isCleanupUser(ticket.getUserId(), prefix, protectedPrefix))
            .toList();
        if (!tickets.isEmpty()) {
            supportTicketRepository.deleteAllInBatch(tickets);
        }
        return tickets.size();
    }

    private long cleanupChatSessions(String prefix, String protectedPrefix, LocalDateTime cutoff) {
        TypedQuery<ChatSession> query = entityManager.createQuery(
            "select s from ChatSession s where s.ownerId like :prefix and s.lastInteractionAt < :cutoff",
            ChatSession.class
        );
        query.setParameter("prefix", prefix + "%");
        query.setParameter("cutoff", cutoff);

        List<ChatSession> sessions = query.getResultList().stream()
            .filter(session -> session != null && isCleanupUser(session.getOwnerId(), prefix, protectedPrefix))
            .toList();
        for (ChatSession session : sessions) {
            entityManager.remove(session);
        }
        return sessions.size();
    }

    private boolean isCleanupUser(String userId, String prefix, String protectedPrefix) {
        return StringUtils.hasText(userId)
            && userId.startsWith(prefix)
            && (!StringUtils.hasText(protectedPrefix) || !userId.startsWith(protectedPrefix));
    }

    @Data
    @Builder
    public static class CleanupResult {
        private boolean success;
        private LocalDateTime cutoff;
        private String demoUserPrefix;
        private String protectedDemoUserPrefix;
        private Map<String, Long> deleted;

        public long deletedCount() {
            return deleted != null ? deleted.values().stream().mapToLong(Long::longValue).sum() : 0L;
        }
    }
}
