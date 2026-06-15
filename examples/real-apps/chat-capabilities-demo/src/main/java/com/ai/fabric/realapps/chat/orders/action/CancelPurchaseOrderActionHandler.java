package com.ai.fabric.realapps.chat.orders.action;

import com.ai.fabric.realapps.chat.orders.domain.PurchaseOrder;
import com.ai.fabric.realapps.chat.orders.service.PurchaseOrderService;
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
    name = "cancel_purchase_order",
    description = "Cancel an existing purchase order by order number (PO-...) or by order id",
    category = "commerce",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
@RequiredArgsConstructor
@Slf4j
public class CancelPurchaseOrderActionHandler {

    private final PurchaseOrderService purchaseOrderService;

    @ActionConfirmation
    public String confirm(
        @Param(value = "orderNumber", description = "Order number (PO-...)", required = true) String orderNumber,
        @Param(value = "orderId", description = "Order id (numeric)") Long orderId
    ) {
        if (orderId != null) {
            return "Cancel order " + orderId + "?";
        }
        if (StringUtils.hasText(orderNumber)) {
            return "Cancel order " + orderNumber.trim() + "?";
        }
        return "Cancel order?";
    }

    @ActionExecute
    public ActionResult execute(
        @Param(value = "orderNumber", description = "Order number (PO-...)", required = true) String orderNumber,
        @Param(value = "orderId", description = "Order id (numeric)") Long orderId,
        ActionContext context
    ) {
        String userId = context != null ? context.userId() : null;
        try {
            String orderRef = orderId != null ? String.valueOf(orderId) : orderNumber;
            PurchaseOrder cancelled = purchaseOrderService.cancelForUser(orderRef, userId);
            String cancelledOrderNumber = cancelled != null ? cancelled.getOrderNumber() : null;
            ActionTargetRef orderTarget = StringUtils.hasText(cancelledOrderNumber)
                ? new ActionTargetRef(
                    cancelledOrderNumber.trim(),
                    "order",
                    "purchase order",
                    Map.of(
                        "orderNumber", cancelledOrderNumber.trim(),
                        "orderId", cancelled.getId() != null ? String.valueOf(cancelled.getId()) : ""
                    )
                )
                : null;
            return ActionResult.builder()
                .success(true)
                .message("Order cancelled")
                .data(ActionResultContracts.object(Map.of(
                    "orderId", cancelled.getId(),
                    "orderNumber", cancelled.getOrderNumber(),
                    "status", cancelled.getStatus() != null ? cancelled.getStatus().name() : null
                )))
                .pinnedTargets(orderTarget != null ? List.of(orderTarget) : null)
                .build();
        } catch (Exception e) {
            log.error("Cancel purchase order failed for user {}", userId, e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to cancel purchase order: " + e.getMessage())
                .errorCode("CANCEL_PURCHASE_ORDER_FAILED")
                .build();
        }
    }
}
