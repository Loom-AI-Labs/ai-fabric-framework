package com.ai.fabric.realapps.chat.orders.action;

import com.ai.fabric.realapps.chat.orders.domain.PurchaseOrder;
import com.ai.fabric.realapps.chat.orders.service.PurchaseOrderService;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.intent.action.annotation.Param;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AIAction(
    name = "get_order_details",
    description = "Get purchase order details by order number (PO-...) or numeric id",
    category = "commerce",
    accessMode = ActionAccessMode.READ,
    requiresConfirmation = false
)
@RequiredArgsConstructor
@Slf4j
public class GetOrderDetailsActionHandler {

    private final PurchaseOrderService purchaseOrderService;

    @ActionExecute
    public ActionResult execute(@Param(value = "orderNumberOrId", description = "Order number (PO-...) or numeric id", required = true) String orderNumberOrId,
                                ActionContext context) {
        String userId = context != null ? context.userId() : null;
        try {
            PurchaseOrder order = purchaseOrderService.getForUserByReference(userId, orderNumberOrId);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("orderId", order.getId());
            payload.put("orderNumber", order.getOrderNumber());
            payload.put("status", order.getStatus() != null ? order.getStatus().name() : null);
            payload.put("sku", order.getSku());
            payload.put("productName", order.getProductName());
            payload.put("quantity", order.getQuantity());
            payload.put("unitPrice", order.getUnitPrice());
            payload.put("totalPrice", order.getTotalPrice());
            payload.put("currency", order.getCurrency());
            payload.put("shippingAddress", order.getShippingAddress());
            payload.put("createdAt", order.getCreatedAt());

            return ActionResult.builder()
                .success(true)
                .message("Order details")
                .data(ActionResultContracts.object(payload))
                .build();
        } catch (Exception e) {
            log.error("Get order details failed for user {}", userId, e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to fetch order details: " + (e != null ? e.getMessage() : "unknown"))
                .errorCode("GET_ORDER_DETAILS_FAILED")
                .build();
        }
    }
}
