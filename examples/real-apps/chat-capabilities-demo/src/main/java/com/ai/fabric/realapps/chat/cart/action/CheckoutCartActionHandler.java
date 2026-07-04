package com.ai.fabric.realapps.chat.cart.action;

import com.ai.fabric.realapps.chat.cart.service.CartService;
import com.ai.fabric.realapps.chat.orders.domain.PurchaseOrder;
import com.ai.fabric.realapps.chat.payments.domain.Payment;
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

@AIAction(
    name = "checkout_cart",
    description = "Checkout my active cart and create an order",
    category = "commerce",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
@RequiredArgsConstructor
@Slf4j
public class CheckoutCartActionHandler {

    private final CartService cartService;

    @ActionConfirmation
    public String confirm() {
        return "Checkout your cart and place the order?";
    }

    @ActionExecute
    public ActionResult execute(
        @Param(value = "shippingAddress", description = "Shipping address", required = true) String shippingAddress,
        @Param(value = "email", description = "Customer email", required = true) String email,
        @Param(value = "paymentMethod", description = "Payment method", allowedValues = {"CARD", "PAYPAL", "COD"}) Payment.Method paymentMethod,
        ActionContext context
    ) {
        try {
            String userId = context != null ? context.userId() : null;
            Payment.Method method = paymentMethod != null ? paymentMethod : Payment.Method.CARD;
            PurchaseOrder order = cartService.checkout(userId, shippingAddress, email, method);

            String orderNumber = order != null ? order.getOrderNumber() : null;
            ActionTargetRef orderTarget = org.springframework.util.StringUtils.hasText(orderNumber)
                ? new ActionTargetRef(
                    orderNumber,
                    "order",
                    "purchase order",
                    Map.of(
                        "orderNumber", orderNumber,
                        "orderId", order.getId() != null ? String.valueOf(order.getId()) : ""
                    )
                )
                : null;

            return ActionResult.builder()
                .success(true)
                .message("Checkout complete. Order " + order.getOrderNumber() + " was created for "
                    + order.getCurrency() + " " + order.getTotalPrice() + ".")
                .data(ActionResultContracts.object(Map.of(
                    "orderId", order.getId(),
                    "orderNumber", order.getOrderNumber(),
                    "totalPrice", order.getTotalPrice(),
                    "currency", order.getCurrency(),
                    "status", order.getStatus() != null ? order.getStatus().name() : null
                )))
                .pinnedTargets(orderTarget != null ? List.of(orderTarget) : null)
                .build();
        } catch (Exception e) {
            String userId = context != null ? context.userId() : null;
            log.error("Checkout cart failed for user {}", userId, e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to checkout cart: " + e.getMessage())
                .errorCode("CHECKOUT_CART_FAILED")
                .build();
        }
    }
}
