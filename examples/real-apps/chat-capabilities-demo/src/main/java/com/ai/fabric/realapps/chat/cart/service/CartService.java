package com.ai.fabric.realapps.chat.cart.service;

import com.ai.fabric.realapps.chat.cart.domain.Cart;
import com.ai.fabric.realapps.chat.cart.domain.CartItem;
import com.ai.fabric.realapps.chat.cart.repo.CartRepository;
import com.ai.fabric.realapps.chat.catalog.domain.Product;
import com.ai.fabric.realapps.chat.catalog.service.ProductService;
import com.ai.fabric.realapps.chat.orders.domain.PurchaseOrder;
import com.ai.fabric.realapps.chat.orders.service.PurchaseOrderService;
import com.ai.fabric.realapps.chat.payments.domain.Payment;
import com.ai.fabric.realapps.chat.payments.repo.PaymentRepository;
import com.ai.fabric.realapps.chat.promotions.domain.Coupon;
import com.ai.fabric.realapps.chat.promotions.service.CouponService;
import com.ai.fabric.realapps.chat.shipping.domain.Shipment;
import com.ai.fabric.realapps.chat.shipping.repo.ShipmentRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRepository cartRepository;
    private final ProductService productService;
    private final CouponService couponService;
    private final PurchaseOrderService purchaseOrderService;
    private final PaymentRepository paymentRepository;
    private final ShipmentRepository shipmentRepository;

    @Transactional(readOnly = true)
    public Cart get(long id) {
        return cartRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Cart not found: " + id));
    }

    @Transactional
    public Cart getOrCreateActiveCart(String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("userId is required");
        }
        String normalizedUserId = userId.trim();

        return cartRepository.findFirstByUserIdAndStatusOrderByUpdatedAtDesc(normalizedUserId, Cart.Status.ACTIVE)
            .orElseGet(() -> {
                Cart cart = new Cart();
                cart.setUserId(normalizedUserId);
                cart.setStatus(Cart.Status.ACTIVE);
                cart.setCurrency("USD");
                recomputeTotals(cart);
                return cartRepository.save(cart);
            });
    }

    @Transactional
    public Cart addItem(String userId, String productReference, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        Cart cart = getOrCreateActiveCart(userId);

        Product product = productService.findBySkuOrName(productReference)
            .orElseThrow(() -> new EntityNotFoundException("Product not found for sku or title: " + productReference));

        String normalizedSku = product.getSku();
        CartItem existing = cart.getItems().stream()
            .filter(i -> i != null && i.getSku() != null && i.getSku().equalsIgnoreCase(normalizedSku))
            .findFirst()
            .orElse(null);

        if (existing == null) {
            CartItem item = new CartItem();
            item.setCart(cart);
            item.setSku(normalizedSku);
            item.setProductId(product.getId());
            item.setProductName(product.getName());
            item.setQuantity(quantity);
            item.setUnitPrice(product.getPrice());
            item.setTotalPrice(product.getPrice().multiply(BigDecimal.valueOf(quantity)));
            cart.getItems().add(item);
        } else {
            int newQty = (existing.getQuantity() != null ? existing.getQuantity() : 0) + quantity;
            existing.setQuantity(newQty);
            existing.setUnitPrice(product.getPrice());
            existing.setTotalPrice(product.getPrice().multiply(BigDecimal.valueOf(newQty)));
        }

        recomputeTotals(cart);
        return cartRepository.save(cart);
    }

    @Transactional
    public Cart removeItem(String userId, String sku) {
        Cart cart = getOrCreateActiveCart(userId);
        if (!StringUtils.hasText(sku)) {
            return cart;
        }

        String normalizedSku = sku.trim().toLowerCase(Locale.ROOT);
        cart.getItems().removeIf(i -> i != null && i.getSku() != null && i.getSku().trim().toLowerCase(Locale.ROOT).equals(normalizedSku));
        recomputeTotals(cart);
        return cartRepository.save(cart);
    }

    @Transactional
    public Cart applyCoupon(String userId, String code) {
        Cart cart = getOrCreateActiveCart(userId);
        if (!StringUtils.hasText(code)) {
            cart.setCouponCode(null);
            recomputeTotals(cart);
            return cartRepository.save(cart);
        }

        Coupon coupon = couponService.findByCode(code)
            .filter(Coupon::isActive)
            .orElseThrow(() -> new EntityNotFoundException("Coupon not found or inactive: " + code));

        cart.setCouponCode(coupon.getCode());
        recomputeTotals(cart);
        return cartRepository.save(cart);
    }

    @Transactional
    public PurchaseOrder checkout(String userId,
                                 String shippingAddress,
                                 String email,
                                 Payment.Method paymentMethod) {
        if (!StringUtils.hasText(shippingAddress)) {
            throw new IllegalArgumentException("shippingAddress is required");
        }
        if (!StringUtils.hasText(email)) {
            throw new IllegalArgumentException("email is required");
        }

        Cart cart = getOrCreateActiveCart(userId);
        if (cart.getItems() == null || cart.getItems().isEmpty()) {
            throw new IllegalStateException("Cart is empty");
        }

        recomputeTotals(cart);

        PurchaseOrder order = purchaseOrderService.createFromCart(cart, shippingAddress.trim(), email.trim());

        cart.setStatus(Cart.Status.CHECKED_OUT);
        cartRepository.save(cart);

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setUserId(order.getUserId());
        payment.setMethod(paymentMethod != null ? paymentMethod : Payment.Method.CARD);
        payment.setStatus(Payment.Status.CAPTURED);
        payment.setTransactionId("TX-" + UUID.randomUUID());
        payment.setAmount(order.getTotalPrice());
        payment.setCurrency(order.getCurrency());
        paymentRepository.save(payment);

        Shipment shipment = new Shipment();
        shipment.setOrder(order);
        shipment.setUserId(order.getUserId());
        shipment.setCarrier("DemoCarrier");
        shipment.setTrackingNumber("TRK-" + UUID.randomUUID());
        shipment.setStatus(Shipment.Status.CREATED);
        shipment.setEta(LocalDateTime.now().plusDays(3));
        shipmentRepository.save(shipment);

        return order;
    }

    private void recomputeTotals(Cart cart) {
        if (cart == null) {
            return;
        }
        BigDecimal subtotal = BigDecimal.ZERO;
        if (cart.getItems() != null) {
            for (CartItem item : cart.getItems()) {
                if (item == null) {
                    continue;
                }
                BigDecimal row = item.getTotalPrice();
                if (row != null) {
                    subtotal = subtotal.add(row);
                }
            }
        }

        BigDecimal discount = BigDecimal.ZERO;
        if (StringUtils.hasText(cart.getCouponCode())) {
            Coupon coupon = couponService.findByCode(cart.getCouponCode()).filter(Coupon::isActive).orElse(null);
            if (coupon != null) {
                if (coupon.getDiscountPercent() != null && coupon.getDiscountPercent() > 0) {
                    BigDecimal percent = BigDecimal.valueOf(coupon.getDiscountPercent()).divide(BigDecimal.valueOf(100));
                    discount = subtotal.multiply(percent);
                } else if (coupon.getDiscountAmount() != null) {
                    discount = coupon.getDiscountAmount();
                }
                if (discount.compareTo(subtotal) > 0) {
                    discount = subtotal;
                }
            }
        }

        BigDecimal total = subtotal.subtract(discount);
        if (total.compareTo(BigDecimal.ZERO) < 0) {
            total = BigDecimal.ZERO;
        }

        cart.setSubtotal(subtotal);
        cart.setDiscount(discount);
        cart.setTotal(total);
        if (!StringUtils.hasText(cart.getCurrency())) {
            cart.setCurrency("USD");
        }
    }
}
