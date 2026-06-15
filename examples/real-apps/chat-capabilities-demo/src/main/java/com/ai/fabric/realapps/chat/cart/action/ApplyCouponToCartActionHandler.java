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
    name = "apply_coupon_to_cart",
    description = "Apply a coupon code to my active cart",
    category = "commerce",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
@RequiredArgsConstructor
@Slf4j
public class ApplyCouponToCartActionHandler {

    private final CartService cartService;

    @ActionConfirmation
    public String confirm(@Param(value = "code", description = "Coupon code", required = true) String code) {
        if (StringUtils.hasText(code)) {
            return "Apply coupon " + code.trim() + " to your cart?";
        }
        return "Apply coupon to cart?";
    }

    @ActionExecute
    public ActionResult execute(@Param(value = "code", description = "Coupon code", required = true) String code,
                                ActionContext context) {
        try {
            String userId = context != null ? context.userId() : null;
            Cart cart = cartService.applyCoupon(userId, code);
            String cartId = cart != null && cart.getId() != null ? String.valueOf(cart.getId()) : null;
            ActionTargetRef cartTarget = cartId != null
                ? new ActionTargetRef(cartId, "cart", "active cart", Map.of("cartId", cartId))
                : null;
            return ActionResult.builder()
                .success(true)
                .message("Coupon applied")
                .data(ActionResultContracts.object(Map.of(
                    "cartId", cart.getId(),
                    "couponCode", cart.getCouponCode(),
                    "subtotal", cart.getSubtotal(),
                    "discount", cart.getDiscount(),
                    "total", cart.getTotal(),
                    "currency", cart.getCurrency()
                )))
                .pinnedTargets(cartTarget != null ? List.of(cartTarget) : null)
                .build();
        } catch (Exception e) {
            String userId = context != null ? context.userId() : null;
            log.error("Apply coupon failed for user {}", userId, e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to apply coupon: " + e.getMessage())
                .errorCode("APPLY_COUPON_FAILED")
                .build();
        }
    }
}
