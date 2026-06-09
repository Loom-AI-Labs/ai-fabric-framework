package com.ai.fabric.realapps.chat.orders.action;

import com.ai.fabric.realapps.chat.orders.domain.PurchaseOrder;
import com.ai.fabric.realapps.chat.orders.service.PurchaseOrderService;
import com.ai.infrastructure.intent.action.ActionAccessMode;
import com.ai.infrastructure.intent.action.ActionContext;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.action.ActionResultContracts;
import com.ai.infrastructure.intent.action.ActionTargetRef;
import com.ai.infrastructure.intent.action.annotation.AIAction;
import com.ai.infrastructure.intent.action.annotation.ActionConfirmation;
import com.ai.infrastructure.intent.action.annotation.ActionExecute;
import com.ai.infrastructure.intent.action.annotation.Param;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

@AIAction(
    name = "create_purchase_order",
    description = "Create a purchase order for a product SKU",
    category = "commerce",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
@RequiredArgsConstructor
@Slf4j
public class CreatePurchaseOrderActionHandler {

    private final PurchaseOrderService purchaseOrderService;

    @ActionConfirmation
    public String confirm(
        @Param(value = "sku", description = "Product SKU", required = true) String sku,
        @Param(value = "quantity", description = "Quantity", required = true, min = 1) Integer quantity
    ) {
        if (StringUtils.hasText(sku) && quantity != null) {
            return "Create purchase order for " + quantity + " × " + sku + "?";
        }
        return "Create purchase order?";
    }

    @ActionExecute
    public ActionResult execute(
        @Param(value = "sku", description = "Product SKU", required = true) String sku,
        @Param(value = "quantity", description = "Quantity", required = true, min = 1) Integer quantity,
        @Param(value = "shippingAddress", description = "Shipping address", required = true) String shippingAddress,
        @Param(value = "email", description = "Customer email", required = true, pattern = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") String email,
        ActionContext context
    ) {
        String userId = context != null ? context.userId() : null;
        try {
            PurchaseOrder created = purchaseOrderService.createPurchaseOrder(
                userId,
                sku,
                quantity != null ? quantity : 1,
                shippingAddress,
                email
            );

            String orderNumber = created != null ? created.getOrderNumber() : null;
            ActionTargetRef orderTarget = StringUtils.hasText(orderNumber)
                ? new ActionTargetRef(
                    orderNumber.trim(),
                    "order",
                    "purchase order",
                    Map.of(
                        "orderNumber", orderNumber.trim(),
                        "orderId", created.getId() != null ? String.valueOf(created.getId()) : "",
                        "sku", created.getSku() != null ? created.getSku() : ""
                    )
                )
                : null;

            return ActionResult.builder()
                .success(true)
                .message("Purchase order created")
                .data(ActionResultContracts.object(Map.of(
                    "orderId", created.getId(),
                    "orderNumber", created.getOrderNumber(),
                    "sku", created.getSku(),
                    "quantity", created.getQuantity(),
                    "totalPrice", created.getTotalPrice(),
                    "currency", created.getCurrency(),
                    "status", created.getStatus() != null ? created.getStatus().name() : null
                )))
                .pinnedTargets(orderTarget != null ? List.of(orderTarget) : null)
                .build();
        } catch (Exception e) {
            log.error("Create purchase order failed for user {}", userId, e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to create purchase order: " + e.getMessage())
                .errorCode("CREATE_PURCHASE_ORDER_FAILED")
                .build();
        }
    }
}
