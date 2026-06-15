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
    name = "change_delivery_address",
    description = "Change the delivery (shipping) address for the current order (or a specific order number)",
    category = "commerce",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
@RequiredArgsConstructor
@Slf4j
public class ChangeDeliveryAddressActionHandler {

    private final PurchaseOrderService purchaseOrderService;

    @ActionConfirmation
    public String confirm(
        @Param(value = "orderNumber", description = "Order number (PO-...)") String orderNumber,
        @Param(value = "orderId", description = "Order id (numeric)") Long orderId
    ) {
        if (orderId != null) {
            return "Change delivery address for order " + orderId + "?";
        }
        if (StringUtils.hasText(orderNumber)) {
            return "Change delivery address for order " + orderNumber.trim() + "?";
        }
        return "Change delivery address for your current order?";
    }

    @ActionExecute
    public ActionResult execute(
        @Param(value = "shippingAddress", description = "New shipping address", required = true) String shippingAddress,
        @Param(value = "orderNumber", description = "Order number (PO-...)") String orderNumber,
        @Param(value = "orderId", description = "Order id (numeric)") Long orderId,
        ActionContext context
    ) {
        String userId = context != null ? context.userId() : null;
        try {
            String orderRef = orderId != null ? String.valueOf(orderId) : orderNumber;

            PurchaseOrder updated = purchaseOrderService.updateDeliveryAddressForUser(userId, orderRef, shippingAddress);
            String updatedOrderNumber = updated != null ? updated.getOrderNumber() : null;
            ActionTargetRef orderTarget = StringUtils.hasText(updatedOrderNumber)
                ? new ActionTargetRef(
                    updatedOrderNumber.trim(),
                    "order",
                    "purchase order",
                    Map.of(
                        "orderNumber", updatedOrderNumber.trim(),
                        "orderId", updated.getId() != null ? String.valueOf(updated.getId()) : ""
                    )
                )
                : null;
            return ActionResult.builder()
                .success(true)
                .message("Delivery address updated")
                .data(ActionResultContracts.object(Map.of(
                    "orderId", updated.getId(),
                    "orderNumber", updated.getOrderNumber(),
                    "shippingAddress", updated.getShippingAddress(),
                    "status", updated.getStatus() != null ? updated.getStatus().name() : null
                )))
                .pinnedTargets(orderTarget != null ? List.of(orderTarget) : null)
                .build();
        } catch (Exception e) {
            log.error("Change delivery address failed for user {}", userId, e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to change delivery address: " + e.getMessage())
                .errorCode("CHANGE_DELIVERY_ADDRESS_FAILED")
                .build();
        }
    }
}
