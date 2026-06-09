package com.ai.fabric.realapps.chat.orders.action;

import com.ai.fabric.realapps.chat.orders.domain.PurchaseOrder;
import com.ai.fabric.realapps.chat.orders.service.PurchaseOrderService;
import com.ai.infrastructure.intent.action.ActionAccessMode;
import com.ai.infrastructure.intent.action.ActionResultContracts;
import com.ai.infrastructure.intent.action.ActionContext;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.action.annotation.AIAction;
import com.ai.infrastructure.intent.action.annotation.ActionExecute;
import com.ai.infrastructure.intent.action.annotation.Param;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AIAction(
    name = "list_orders",
    description = "List my purchase orders",
    category = "commerce",
    accessMode = ActionAccessMode.READ,
    requiresConfirmation = false
)
@RequiredArgsConstructor
@Slf4j
public class ListOrdersActionHandler {

    private final PurchaseOrderService purchaseOrderService;

    @ActionExecute
    public ActionResult execute(@Param(value = "limit", description = "Max number of orders to return") Integer limit,
                                ActionContext context) {
        String userId = context != null ? context.userId() : null;
        try {
            int effectiveLimit = limit != null ? limit : 50;
            List<PurchaseOrder> orders = purchaseOrderService.listForUser(userId, effectiveLimit);

            List<Map<String, Object>> payload = orders.stream()
                .map(o -> Map.<String, Object>of(
                    "orderId", o.getId(),
                    "orderNumber", o.getOrderNumber(),
                    "status", o.getStatus() != null ? o.getStatus().name() : null,
                    "sku", o.getSku(),
                    "productName", o.getProductName(),
                    "quantity", o.getQuantity(),
                    "totalPrice", o.getTotalPrice(),
                    "currency", o.getCurrency(),
                    "createdAt", o.getCreatedAt()
                ))
                .toList();

            return ActionResult.builder()
                .success(true)
                .message(payload.isEmpty() ? "No orders found" : "Orders")
                .data(ActionResultContracts.list(payload))
                .build();
        } catch (Exception e) {
            log.error("List orders failed for user {}", userId, e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to list orders: " + (e != null ? e.getMessage() : "unknown"))
                .errorCode("LIST_ORDERS_FAILED")
                .build();
        }
    }
}
