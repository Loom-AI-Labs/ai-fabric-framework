package com.ai.fabric.realapps.chat.cart.action;

import com.ai.fabric.realapps.chat.cart.domain.Cart;
import com.ai.fabric.realapps.chat.cart.domain.CartItem;
import com.ai.fabric.realapps.chat.cart.service.CartService;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionExecute;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AIAction(
    name = "view_cart",
    description = "View my current active cart and its items",
    category = "commerce",
    accessMode = ActionAccessMode.READ,
    requiresConfirmation = false
)
@RequiredArgsConstructor
@Slf4j
public class ViewCartActionHandler {

    private final CartService cartService;

    @ActionExecute
    public ActionResult execute(ActionContext context) {
        String userId = context != null ? context.userId() : null;
        try {
            Cart cart = cartService.getOrCreateActiveCart(userId);
            List<CartItem> items = cart.getItems() != null ? cart.getItems() : List.of();

            List<Map<String, Object>> outItems = items.stream()
                .filter(i -> i != null)
                .map(i -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", i.getId());
                    row.put("sku", i.getSku());
                    row.put("productName", i.getProductName());
                    row.put("quantity", i.getQuantity());
                    row.put("unitPrice", i.getUnitPrice());
                    row.put("totalPrice", i.getTotalPrice());
                    return row;
                })
                .toList();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("cartId", cart.getId());
            data.put("status", cart.getStatus() != null ? cart.getStatus().name() : null);
            data.put("currency", cart.getCurrency());
            data.put("couponCode", cart.getCouponCode());
            data.put("subtotal", cart.getSubtotal());
            data.put("discount", cart.getDiscount());
            data.put("total", cart.getTotal());
            data.put("items", outItems);

            return ActionResult.builder()
                .success(true)
                .message(outItems.isEmpty() ? "Your cart is empty" : "Your active cart")
                .data(ActionResultContracts.object(data))
                .build();
        } catch (Exception e) {
            log.error("View cart failed for user {}", userId, e);
            if (e instanceof jakarta.persistence.EntityNotFoundException) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("cartId", null);
                data.put("status", "ACTIVE");
                data.put("currency", "USD");
                data.put("couponCode", null);
                data.put("subtotal", 0);
                data.put("discount", 0);
                data.put("total", 0);
                data.put("items", List.of());

                return ActionResult.builder()
                    .success(true)
                    .message("Your cart is empty")
                    .data(ActionResultContracts.object(data))
                    .build();
            }
            return ActionResult.builder()
                .success(false)
                .message("Failed to fetch cart: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))
                .errorCode("VIEW_CART_FAILED")
                .build();
        }
    }
}
