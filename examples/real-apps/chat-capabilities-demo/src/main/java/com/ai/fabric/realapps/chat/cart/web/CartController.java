package com.ai.fabric.realapps.chat.cart.web;

import com.ai.fabric.realapps.chat.cart.domain.Cart;
import com.ai.fabric.realapps.chat.cart.service.CartService;
import com.ai.fabric.realapps.chat.orders.domain.PurchaseOrder;
import com.ai.fabric.realapps.chat.payments.domain.Payment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/carts")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping("/{id}")
    public Cart get(@PathVariable long id) {
        return cartService.get(id);
    }

    @GetMapping("/active")
    public Cart getActive(@RequestParam("userId") String userId) {
        return cartService.getOrCreateActiveCart(userId);
    }

    @PostMapping("/active/items")
    public Cart addItem(@Valid @RequestBody AddCartItemRequest request) {
        return cartService.addItem(request.getUserId(), request.getSku(), request.getQuantity());
    }

    @DeleteMapping("/active/items")
    public Cart removeItem(@RequestParam("userId") String userId, @RequestParam("sku") String sku) {
        return cartService.removeItem(userId, sku);
    }

    @PostMapping("/active/coupon")
    public Cart applyCoupon(@Valid @RequestBody ApplyCouponRequest request) {
        return cartService.applyCoupon(request.getUserId(), request.getCode());
    }

    @PostMapping("/active/checkout")
    public ResponseEntity<PurchaseOrder> checkout(@Valid @RequestBody CheckoutRequest request) {
        PurchaseOrder order = cartService.checkout(
            request.getUserId(),
            request.getShippingAddress(),
            request.getEmail(),
            request.getPaymentMethod()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @Data
    public static class AddCartItemRequest {
        @NotBlank
        private String userId;
        @NotBlank
        private String sku;
        @Min(1)
        private int quantity;
    }

    @Data
    public static class ApplyCouponRequest {
        @NotBlank
        private String userId;
        @NotBlank
        private String code;
    }

    @Data
    public static class CheckoutRequest {
        @NotBlank
        private String userId;
        @NotBlank
        private String shippingAddress;
        @Email
        @NotBlank
        private String email;
        @NotNull
        private Payment.Method paymentMethod = Payment.Method.CARD;
    }
}
