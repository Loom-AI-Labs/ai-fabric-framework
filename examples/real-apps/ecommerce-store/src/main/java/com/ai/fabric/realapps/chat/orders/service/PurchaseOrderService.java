package com.ai.fabric.realapps.chat.orders.service;

import com.ai.fabric.realapps.chat.catalog.domain.Product;
import com.ai.fabric.realapps.chat.catalog.service.ProductService;
import com.ai.fabric.realapps.chat.cart.domain.Cart;
import com.ai.fabric.realapps.chat.cart.domain.CartItem;
import com.ai.fabric.realapps.chat.orders.domain.OrderItem;
import com.ai.fabric.realapps.chat.orders.domain.PurchaseOrder;
import com.ai.fabric.realapps.chat.orders.repo.OrderItemRepository;
import com.ai.fabric.realapps.chat.orders.repo.PurchaseOrderRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductService productService;

    @Transactional
    public PurchaseOrder createPurchaseOrder(String userId,
                                             String sku,
                                             int quantity,
                                             String shippingAddress,
                                             String email) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("userId is required");
        }
        if (!StringUtils.hasText(sku)) {
            throw new IllegalArgumentException("sku is required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        if (!StringUtils.hasText(shippingAddress)) {
            throw new IllegalArgumentException("shippingAddress is required");
        }
        if (!StringUtils.hasText(email)) {
            throw new IllegalArgumentException("email is required");
        }

        Product product = productService.findBySku(sku.trim())
            .orElseThrow(() -> new EntityNotFoundException("Product not found for sku: " + sku));

        Integer inStock = product.getInStockQty();
        if (inStock == null) {
            inStock = 0;
        }
        if (inStock < quantity) {
            throw new IllegalArgumentException("Insufficient stock for sku '" + product.getSku()
                + "'. Available=" + inStock + " requested=" + quantity);
        }

        BigDecimal unitPrice = product.getPrice();
        if (unitPrice == null) {
            throw new IllegalArgumentException("Product price is not set for sku: " + product.getSku());
        }

        String currency = StringUtils.hasText(product.getCurrency())
            ? product.getCurrency().trim().toUpperCase(Locale.ROOT)
            : "USD";

        BigDecimal totalPrice = unitPrice.multiply(BigDecimal.valueOf(quantity));

        // Update inventory.
        productService.updateProductStock(product.getSku(), inStock - quantity);

        PurchaseOrder order = new PurchaseOrder();
        order.setOrderNumber("PO-" + UUID.randomUUID());
        order.setUserId(userId.trim());
        order.setSku(product.getSku());
        order.setProductId(product.getId());
        order.setProductName(product.getName());
        order.setQuantity(quantity);
        order.setUnitPrice(unitPrice);
        order.setCurrency(currency);
        order.setTotalPrice(totalPrice);
        order.setShippingAddress(shippingAddress.trim());
        order.setEmail(email.trim());
        order.setStatus(PurchaseOrder.Status.CREATED);

        PurchaseOrder saved = purchaseOrderRepository.save(order);

        OrderItem item = new OrderItem();
        item.setOrder(saved);
        item.setSku(product.getSku());
        item.setProductId(product.getId());
        item.setProductName(product.getName());
        item.setQuantity(quantity);
        item.setUnitPrice(unitPrice);
        item.setTotalPrice(totalPrice);
        orderItemRepository.save(item);

        return saved;
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrder> listForUser(String userId, int limit) {
        if (!StringUtils.hasText(userId)) {
            return List.of();
        }
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 200);
        return purchaseOrderRepository.findByUserIdOrderByCreatedAtDesc(userId.trim()).stream()
            .limit(effectiveLimit)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrder> listActiveForUser(String userId, int limit) {
        if (!StringUtils.hasText(userId)) {
            return List.of();
        }
        int effectiveLimit = limit <= 0 ? 25 : Math.min(limit, 200);
        return purchaseOrderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId.trim(), PurchaseOrder.Status.CREATED).stream()
            .limit(effectiveLimit)
            .toList();
    }

    @Transactional(readOnly = true)
    public PurchaseOrder getForUser(long id, String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("userId is required");
        }
        PurchaseOrder order = purchaseOrderRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Order not found: " + id));
        if (!userId.trim().equals(order.getUserId())) {
            throw new EntityNotFoundException("Order not found: " + id);
        }
        return order;
    }

    @Transactional(readOnly = true)
    public PurchaseOrder resolveForUser(String orderNumberOrId, String userId) {
        return getByOrderNumberOrIdForUser(orderNumberOrId, userId);
    }

    @Transactional(readOnly = true)
    public PurchaseOrder getForUserByReference(String userId, String orderNumberOrId) {
        return getByOrderNumberOrIdForUser(orderNumberOrId, userId);
    }

    @Transactional
    public PurchaseOrder cancelForUser(String orderNumber, String userId) {
        PurchaseOrder order = getByOrderNumberOrIdForUser(orderNumber, userId);

        if (order.getStatus() == PurchaseOrder.Status.CANCELLED) {
            throw new IllegalStateException("Order is already cancelled: " + order.getOrderNumber());
        }
        if (order.getStatus() == PurchaseOrder.Status.FULFILLED) {
            throw new IllegalStateException("Order is already fulfilled and cannot be cancelled: " + order.getOrderNumber());
        }

        order.setStatus(PurchaseOrder.Status.CANCELLED);

        List<OrderItem> items = orderItemRepository.findByOrderIdOrderByIdAsc(order.getId());
        if (items != null && !items.isEmpty()) {
            for (OrderItem item : items) {
                if (item == null) {
                    continue;
                }
                String sku = item.getSku();
                int restoreQty = item.getQuantity() != null ? item.getQuantity() : 0;
                if (!StringUtils.hasText(sku) || restoreQty <= 0) {
                    continue;
                }
                productService.findBySku(sku).ifPresent(product -> {
                    Integer inStock = product.getInStockQty();
                    if (inStock == null) {
                        inStock = 0;
                    }
                    productService.updateProductStock(product.getSku(), inStock + restoreQty);
                });
            }
        } else {
            productService.findBySku(order.getSku()).ifPresent(product -> {
                Integer inStock = product.getInStockQty();
                if (inStock == null) {
                    inStock = 0;
                }
                Integer qty = order.getQuantity();
                int restoreQty = qty != null ? qty : 0;
                productService.updateProductStock(product.getSku(), inStock + restoreQty);
            });
        }

        return purchaseOrderRepository.save(order);
    }

    @Transactional
    public PurchaseOrder createFromCart(Cart cart, String shippingAddress, String email) {
        if (cart == null) {
            throw new IllegalArgumentException("cart is required");
        }
        if (!StringUtils.hasText(cart.getUserId())) {
            throw new IllegalArgumentException("cart.userId is required");
        }
        if (!StringUtils.hasText(shippingAddress)) {
            throw new IllegalArgumentException("shippingAddress is required");
        }
        if (!StringUtils.hasText(email)) {
            throw new IllegalArgumentException("email is required");
        }
        if (cart.getItems() == null || cart.getItems().isEmpty()) {
            throw new IllegalArgumentException("cart has no items");
        }

        String userId = cart.getUserId().trim();

        BigDecimal total = BigDecimal.ZERO;
        BigDecimal unitPriceForHeader = BigDecimal.ZERO;
        String skuForHeader = "MULTI";
        String nameForHeader = "Multiple items";
        Long productIdForHeader = null;
        int qtyForHeader = 0;
        String currency = StringUtils.hasText(cart.getCurrency()) ? cart.getCurrency().trim().toUpperCase(Locale.ROOT) : "USD";

        for (CartItem item : cart.getItems()) {
            if (item == null) {
                continue;
            }
            if (!StringUtils.hasText(item.getSku())) {
                throw new IllegalArgumentException("cart item sku is required");
            }
            int qty = item.getQuantity() != null ? item.getQuantity() : 0;
            if (qty <= 0) {
                throw new IllegalArgumentException("cart item quantity must be > 0");
            }

            Product product = productService.findBySku(item.getSku())
                .orElseThrow(() -> new EntityNotFoundException("Product not found for sku: " + item.getSku()));

            Integer inStock = product.getInStockQty() != null ? product.getInStockQty() : 0;
            if (inStock < qty) {
                throw new IllegalArgumentException("Insufficient stock for sku '" + product.getSku()
                    + "'. Available=" + inStock + " requested=" + qty);
            }
            if (product.getPrice() == null) {
                throw new IllegalArgumentException("Product price is not set for sku: " + product.getSku());
            }

            BigDecimal rowTotal = product.getPrice().multiply(BigDecimal.valueOf(qty));
            total = total.add(rowTotal);
            qtyForHeader += qty;

            // Update inventory per item
            productService.updateProductStock(product.getSku(), inStock - qty);

            // Header fields: keep first item for backwards compatibility with existing API fields.
            if (productIdForHeader == null) {
                productIdForHeader = product.getId();
                skuForHeader = product.getSku();
                nameForHeader = product.getName();
                unitPriceForHeader = product.getPrice();
            }
        }

        PurchaseOrder order = new PurchaseOrder();
        order.setOrderNumber("PO-" + UUID.randomUUID());
        order.setUserId(userId);
        order.setSku(skuForHeader);
        order.setProductId(productIdForHeader);
        order.setProductName(nameForHeader);
        order.setQuantity(qtyForHeader);
        order.setUnitPrice(unitPriceForHeader);
        order.setCurrency(currency);
        order.setTotalPrice(total);
        order.setShippingAddress(shippingAddress.trim());
        order.setEmail(email.trim());
        order.setStatus(PurchaseOrder.Status.CREATED);

        PurchaseOrder saved = purchaseOrderRepository.save(order);

        for (CartItem item : cart.getItems()) {
            if (item == null) {
                continue;
            }
            Product product = productService.findBySku(item.getSku())
                .orElseThrow(() -> new EntityNotFoundException("Product not found for sku: " + item.getSku()));
            int qty = item.getQuantity() != null ? item.getQuantity() : 0;
            BigDecimal rowTotal = product.getPrice().multiply(BigDecimal.valueOf(qty));

            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(saved);
            orderItem.setSku(product.getSku());
            orderItem.setProductId(product.getId());
            orderItem.setProductName(product.getName());
            orderItem.setQuantity(qty);
            orderItem.setUnitPrice(product.getPrice());
            orderItem.setTotalPrice(rowTotal);
            orderItemRepository.save(orderItem);
        }

        return saved;
    }

    @Transactional(readOnly = true)
    public List<OrderItem> listItemsForUser(long orderId, String userId) {
        PurchaseOrder order = getForUser(orderId, userId);
        return orderItemRepository.findByOrderIdOrderByIdAsc(order.getId());
    }

    @Transactional
    public PurchaseOrder updateDeliveryAddressForUser(String userId, String orderNumber, String newShippingAddress) {
        if (!StringUtils.hasText(newShippingAddress)) {
            throw new IllegalArgumentException("shippingAddress is required");
        }

        PurchaseOrder order = StringUtils.hasText(orderNumber)
            ? getByOrderNumberOrIdForUser(orderNumber, userId)
            : getCurrentCreatedOrderForUser(userId);

        if (order.getStatus() == PurchaseOrder.Status.CANCELLED) {
            throw new IllegalStateException("Order is cancelled: " + order.getOrderNumber());
        }
        if (order.getStatus() == PurchaseOrder.Status.FULFILLED) {
            throw new IllegalStateException("Order is already fulfilled and cannot be changed: " + order.getOrderNumber());
        }

        order.setShippingAddress(newShippingAddress.trim());
        return purchaseOrderRepository.save(order);
    }

    private PurchaseOrder getByOrderNumberOrIdForUser(String orderNumberOrId, String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("userId is required");
        }
        if (!StringUtils.hasText(orderNumberOrId)) {
            throw new IllegalArgumentException("orderNumber is required");
        }

        String normalized = orderNumberOrId.trim();
        Optional<Long> asId = parseLongIfDigitsOnly(normalized);
        if (asId.isPresent()) {
            return getForUser(asId.get(), userId);
        }

        PurchaseOrder order = purchaseOrderRepository.findByOrderNumber(normalized)
            .orElseThrow(() -> new EntityNotFoundException("Order not found: " + normalized));

        if (!userId.trim().equals(order.getUserId())) {
            throw new EntityNotFoundException("Order not found: " + normalized);
        }

        return order;
    }

    private PurchaseOrder getCurrentCreatedOrderForUser(String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("userId is required");
        }
        return purchaseOrderRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId.trim(), PurchaseOrder.Status.CREATED)
            .orElseThrow(() -> new EntityNotFoundException("No current order found for user: " + userId.trim()));
    }

    private Optional<Long> parseLongIfDigitsOnly(String value) {
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                return Optional.empty();
            }
        }
        try {
            return Optional.of(Long.parseLong(trimmed));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }
}
