package com.ai.fabric.realapps.chat.orders.action;

import com.ai.fabric.realapps.chat.orders.domain.PurchaseOrder;
import com.ai.fabric.realapps.chat.orders.service.PurchaseOrderService;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.intent.action.annotation.Param;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AIAction(
    name = "get_active_orders",
    description = "List my current active (not cancelled/fulfilled) purchase orders",
    category = "commerce",
    accessMode = ActionAccessMode.READ,
    requiresConfirmation = false
)
@RequiredArgsConstructor
@Slf4j
public class GetActiveOrdersActionHandler {

    private final PurchaseOrderService purchaseOrderService;

    @ActionExecute
    public ActionResult execute(@Param(value = "limit", description = "Max number of orders to return") Integer limit,
                                ActionContext context) {
        String userId = context != null ? context.userId() : null;
        try {
            int effectiveLimit = limit != null ? limit : 25;
            List<PurchaseOrder> orders = purchaseOrderService.listActiveForUser(userId, effectiveLimit);
            List<Map<String, Object>> payload = orders.stream()
                .map(o -> Map.<String, Object>of(
                    "orderId", o.getId(),
                    "orderNumber", o.getOrderNumber(),
                    "status", o.getStatus() != null ? o.getStatus().name() : null,
                    "sku", o.getSku(),
                    "quantity", o.getQuantity(),
                    "totalPrice", o.getTotalPrice(),
                    "currency", o.getCurrency(),
                    "shippingAddress", o.getShippingAddress(),
                    "createdAt", o.getCreatedAt()
                ))
                .toList();

            return ActionResult.builder()
                .success(true)
                .message(payload.isEmpty() ? "No active orders found" : "Active orders")
                .data(ActionResultContracts.list(payload))
                .build();
        } catch (Exception e) {
            log.error("Get active orders failed for user {}", userId, e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to fetch active orders: " + e.getMessage())
                .errorCode("GET_ACTIVE_ORDERS_FAILED")
                .build();
        }
    }
}
