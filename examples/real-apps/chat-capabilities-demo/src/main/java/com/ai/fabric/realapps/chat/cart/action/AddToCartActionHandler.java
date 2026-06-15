package com.ai.fabric.realapps.chat.cart.action;

import com.ai.fabric.realapps.chat.cart.domain.Cart;
import com.ai.fabric.realapps.chat.cart.service.CartService;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.ActionTargetRef;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionConfirmation;
import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.intent.action.annotation.Param;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

@AIAction(
    name = "add_to_cart",
    description = "Add one or more product SKUs to my active cart",
    category = "commerce",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
@RequiredArgsConstructor
@Slf4j
public class AddToCartActionHandler {

    private final CartService cartService;

    public record CartItemInput(
        String sku,
        Integer quantity
    ) {
    }

    @ActionConfirmation
    public String confirm(
        @Param(value = "items", description = "Cart items to add", required = true, batchTargets = true) List<CartItemInput> items
    ) {
        if (items == null || items.isEmpty()) {
            return "Add items to your cart?";
        }
        if (items.size() == 1) {
            CartItemInput item = items.getFirst();
            String sku = item != null ? item.sku() : null;
            Integer qty = item != null ? item.quantity() : null;
            if (StringUtils.hasText(sku) && qty != null) {
                return "Add " + qty + " × " + sku.trim() + " to your cart?";
            }
            if (StringUtils.hasText(sku)) {
                return "Add 1 × " + sku.trim() + " to your cart?";
            }
            return "Add item to your cart?";
        }

        List<String> parts = new ArrayList<>();
        for (CartItemInput item : items) {
            if (item == null || !StringUtils.hasText(item.sku())) {
                continue;
            }
            int qty = item.quantity() != null ? item.quantity() : 1;
            parts.add(qty + " × " + item.sku().trim());
            if (parts.size() >= 4) {
                break;
            }
        }

        String tail = parts.size() < items.size() ? " …" : "";
        String joined = parts.isEmpty() ? "" : " (" + String.join(", ", parts) + tail + ")";
        return "Add " + items.size() + " items to your cart?" + joined;
    }

    @ActionExecute
    public ActionResult execute(
        @Param(value = "items", description = "Cart items to add", required = true, batchTargets = true) List<CartItemInput> items,
        ActionContext context
    ) {
        try {
            if (items == null || items.isEmpty()) {
                return ActionResult.builder()
                    .success(false)
                    .message("No items provided")
                    .errorCode("CART_ITEMS_REQUIRED")
                    .build();
            }
            String userId = context != null ? context.userId() : null;
            Cart cart = null;
            for (CartItemInput item : items) {
                if (item == null || !StringUtils.hasText(item.sku())) {
                    continue;
                }
                int qty = item.quantity() != null ? item.quantity() : 1;
                cart = cartService.addItem(userId, item.sku(), qty);
            }
            if (cart == null) {
                return ActionResult.builder()
                    .success(false)
                    .message("No valid items provided")
                    .errorCode("CART_ITEMS_INVALID")
                    .build();
            }
            String cartId = cart.getId() != null ? String.valueOf(cart.getId()) : null;
            ActionTargetRef cartTarget = cartId != null
                ? new ActionTargetRef(cartId, "cart", "active cart", Map.of("cartId", cartId))
                : null;
            return ActionResult.builder()
                .success(true)
                .message("Added to cart")
                .data(ActionResultContracts.object(Map.of(
                    "cartId", cart.getId(),
                    "total", cart.getTotal(),
                    "currency", cart.getCurrency(),
                    "itemsCount", cart.getItems() != null ? cart.getItems().size() : 0
                )))
                .pinnedTargets(cartTarget != null ? List.of(cartTarget) : null)
                .build();
        } catch (Exception e) {
            String userId = context != null ? context.userId() : null;
            log.error("Add to cart failed for user {}", userId, e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to add to cart: " + e.getMessage())
                .errorCode("ADD_TO_CART_FAILED")
                .build();
        }
    }
}
