package com.ai.fabric.realapps.chat.orders.action;

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
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

@AIAction(
    name = OfferOrderDiscountActionHandler.ACTION_NAME,
    description = "Offer a retention discount to keep an order active instead of cancelling it",
    category = "commerce",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
@Slf4j
public class OfferOrderDiscountActionHandler {

    public static final String ACTION_NAME = "offer_order_discount";

    @ActionConfirmation
    public String confirm(@Param(value = "discountPercent", description = "Discount percent") Integer discountPercent) {
        int percent = discountPercent != null ? discountPercent : 10;
        return "Apply a " + percent + "% discount to keep your order instead of cancelling?";
    }

    @ActionExecute
    public ActionResult execute(
        @Param(value = "orderNumber", description = "Order number (PO-...)") String orderNumber,
        @Param(value = "orderId", description = "Order id (numeric)") Long orderId,
        @Param(value = "discountPercent", description = "Discount percent") Integer discountPercent,
        ActionContext context
    ) {
        String userId = context != null ? context.userId() : null;
        try {
            int percent = discountPercent != null ? discountPercent : 10;
            String ref = StringUtils.hasText(orderNumber)
                ? orderNumber.trim()
                : (orderId != null ? String.valueOf(orderId) : "your order");
            String coupon = percent >= 15 ? "SAVE15" : "SAVE10";

            log.info("Issued retention offer {}% (coupon={}) for user={} orderRef={}", percent, coupon, userId, ref);

            ActionTargetRef orderTarget = StringUtils.hasText(ref) && !"your order".equals(ref)
                ? new ActionTargetRef(ref, "order", "purchase order", Map.of("orderRef", ref))
                : null;
            return ActionResult.builder()
                .success(true)
                .message("Discount offer applied")
                .data(ActionResultContracts.object(Map.of(
                    "orderRef", ref,
                    "discountPercent", percent,
                    "couponCode", coupon,
                    "note", "This demo does not persist discounts to the order record."
                )))
                .pinnedTargets(orderTarget != null ? List.of(orderTarget) : null)
                .build();
        } catch (Exception e) {
            log.error("Offer discount failed for user {}", userId, e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to apply discount offer: " + (e != null ? e.getMessage() : "unknown"))
                .errorCode("OFFER_ORDER_DISCOUNT_FAILED")
                .build();
        }
    }
}
