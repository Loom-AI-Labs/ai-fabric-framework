package com.ai.fabric.realapps.chat.orders.web;

import com.ai.fabric.realapps.chat.orders.domain.PurchaseOrder;
import com.ai.fabric.realapps.chat.orders.domain.OrderItem;
import com.ai.fabric.realapps.chat.orders.service.PurchaseOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @GetMapping("/active")
    public List<PurchaseOrder> listActive(@RequestParam("userId") String userId,
                                          @RequestParam(value = "limit", defaultValue = "25") int limit) {
        return purchaseOrderService.listActiveForUser(userId, limit);
    }

    /**
     * Resolve an order by reference (order number like PO-... or numeric id).
     */
    @GetMapping("/resolve")
    public PurchaseOrder resolve(@RequestParam("userId") String userId,
                                 @RequestParam("orderNumberOrId") String orderNumberOrId) {
        return purchaseOrderService.resolveForUser(orderNumberOrId, userId);
    }

    /**
     * Create a purchase order directly (bypassing cart checkout).
     *
     * <p>This exists to support the {@code create_purchase_order} action when using the Generic REST Connector.</p>
     */
    @PostMapping
    public ResponseEntity<PurchaseOrder> create(@Valid @RequestBody CreateOrderRequest request) {
        PurchaseOrder created = purchaseOrderService.createPurchaseOrder(
            request.getUserId(),
            request.getSku(),
            request.getQuantity(),
            request.getShippingAddress(),
            request.getEmail()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Cancel an order by order number (PO-...) or numeric id.
     *
     * <p>This exists to support the {@code cancel_purchase_order} action when using the Generic REST Connector.</p>
     */
    @PostMapping("/cancel")
    public PurchaseOrder cancel(@Valid @RequestBody CancelOrderRequest request) {
        String ref = orderRef(request.getOrderNumber(), request.getOrderId());
        return purchaseOrderService.cancelForUser(ref, request.getUserId());
    }

    /**
     * Change delivery address for the current order or a provided order reference.
     *
     * <p>This exists to support the {@code change_delivery_address} action when using the Generic REST Connector.</p>
     */
    @PostMapping("/delivery-address")
    public PurchaseOrder changeDeliveryAddress(@Valid @RequestBody ChangeDeliveryAddressRequest request) {
        String ref = orderRefNullable(request.getOrderNumber(), request.getOrderId());
        return purchaseOrderService.updateDeliveryAddressForUser(request.getUserId(), ref, request.getShippingAddress());
    }

    /**
     * Demo retention discount offer. Not persisted.
     *
     * <p>This exists to support the {@code offer_order_discount} action when using the Generic REST Connector.</p>
     */
    @PostMapping("/discount-offer")
    public Map<String, Object> discountOffer(@Valid @RequestBody DiscountOfferRequest request) {
        int percent = request.getDiscountPercent() != null ? request.getDiscountPercent() : 10;
        String ref = orderRefNullable(request.getOrderNumber(), request.getOrderId());
        String coupon = percent >= 15 ? "SAVE15" : "SAVE10";
        return Map.of(
            "orderRef", StringUtils.hasText(ref) ? ref.trim() : "your order",
            "discountPercent", percent,
            "couponCode", coupon,
            "note", "This demo does not persist discounts to the order record."
        );
    }

    @GetMapping("/{id}")
    public PurchaseOrder get(@PathVariable long id, @RequestParam("userId") String userId) {
        return purchaseOrderService.getForUser(id, userId);
    }

    @GetMapping("/{id}/items")
    public List<OrderItem> listItems(@PathVariable long id, @RequestParam("userId") String userId) {
        return purchaseOrderService.listItemsForUser(id, userId);
    }

    private String orderRef(String orderNumber, Long orderId) {
        String ref = orderRefNullable(orderNumber, orderId);
        if (!StringUtils.hasText(ref)) {
            throw new IllegalArgumentException("orderNumber or orderId is required");
        }
        return ref.trim();
    }

    private String orderRefNullable(String orderNumber, Long orderId) {
        if (orderId != null) {
            return String.valueOf(orderId);
        }
        return StringUtils.hasText(orderNumber) ? orderNumber.trim() : null;
    }

    @Data
    public static class CreateOrderRequest {
        @NotBlank
        private String userId;
        @NotBlank
        private String sku;
        @Min(1)
        private int quantity;
        @NotBlank
        private String shippingAddress;
        @Email
        @NotBlank
        private String email;
    }

    @Data
    public static class CancelOrderRequest {
        @NotBlank
        private String userId;
        private String orderNumber;
        private Long orderId;
    }

    @Data
    public static class ChangeDeliveryAddressRequest {
        @NotBlank
        private String userId;
        @NotBlank
        private String shippingAddress;
        private String orderNumber;
        private Long orderId;
    }

    @Data
    public static class DiscountOfferRequest {
        @NotBlank
        private String userId;
        private String orderNumber;
        private Long orderId;
        private Integer discountPercent;
    }
}
