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
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

@AIAction(
    name = "remove_from_cart",
    description = "Remove a product SKU from my active cart",
    category = "commerce",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
@RequiredArgsConstructor
@Slf4j
public class RemoveFromCartActionHandler {

    private final CartService cartService;

    @ActionConfirmation
    public String confirm(@Param(value = "sku", description = "Product SKU", required = true) String sku) {
        if (StringUtils.hasText(sku)) {
            return "Remove " + sku.trim() + " from your cart?";
        }
        return "Remove item from cart?";
    }

    @ActionExecute
    public ActionResult execute(@Param(value = "sku", description = "Product SKU", required = true) String sku,
                                ActionContext context) {
        try {
            String userId = context != null ? context.userId() : null;
            Cart cart = cartService.removeItem(userId, sku);
            String cartId = cart != null && cart.getId() != null ? String.valueOf(cart.getId()) : null;
            ActionTargetRef cartTarget = cartId != null
                ? new ActionTargetRef(cartId, "cart", "active cart", Map.of("cartId", cartId))
                : null;
            return ActionResult.builder()
                .success(true)
                .message("Removed from cart")
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
            log.error("Remove from cart failed for user {}", userId, e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to remove from cart: " + e.getMessage())
                .errorCode("REMOVE_FROM_CART_FAILED")
                .build();
        }
    }
}
