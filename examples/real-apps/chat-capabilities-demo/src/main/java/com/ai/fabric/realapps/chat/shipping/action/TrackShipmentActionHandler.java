package com.ai.fabric.realapps.chat.shipping.action;

import com.ai.fabric.realapps.chat.orders.domain.PurchaseOrder;
import com.ai.fabric.realapps.chat.orders.service.PurchaseOrderService;
import com.ai.fabric.realapps.chat.shipping.domain.Shipment;
import com.ai.fabric.realapps.chat.shipping.service.ShipmentService;
import com.ai.infrastructure.intent.action.ActionAccessMode;
import com.ai.infrastructure.intent.action.ActionContext;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.action.ActionResultContracts;
import com.ai.infrastructure.intent.action.annotation.AIAction;
import com.ai.infrastructure.intent.action.annotation.ActionExecute;
import com.ai.infrastructure.intent.action.annotation.Param;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AIAction(
    name = "track_shipment",
    description = "Track the latest shipment for an order (by order number or id)",
    category = "commerce",
    accessMode = ActionAccessMode.READ,
    requiresConfirmation = false
)
@RequiredArgsConstructor
@Slf4j
public class TrackShipmentActionHandler {

    private final PurchaseOrderService purchaseOrderService;
    private final ShipmentService shipmentService;

    @ActionExecute
    public ActionResult execute(@Param(value = "orderNumber", description = "Order number (PO-...) or order id", required = true) String orderNumber,
                                ActionContext context) {
        String userId = context != null ? context.userId() : null;
        try {
            PurchaseOrder order = purchaseOrderService.resolveForUser(orderNumber, userId);

            Shipment shipment = shipmentService.findLatestForOrder(order.getId())
                .orElse(null);

            if (shipment == null) {
                return ActionResult.builder()
                    .success(true)
                    .message("No shipment found for this order yet")
                    .data(ActionResultContracts.object(Map.of(
                        "orderId", order.getId(),
                        "orderNumber", order.getOrderNumber()
                    )))
                    .build();
            }

            return ActionResult.builder()
                .success(true)
                .message("Shipment status")
                .data(ActionResultContracts.object(Map.of(
                    "orderId", order.getId(),
                    "orderNumber", order.getOrderNumber(),
                    "shipmentId", shipment.getId(),
                    "carrier", shipment.getCarrier(),
                    "trackingNumber", shipment.getTrackingNumber(),
                    "status", shipment.getStatus() != null ? shipment.getStatus().name() : null,
                    "eta", shipment.getEta()
                )))
                .build();
        } catch (Exception e) {
            log.error("Track shipment failed for user {}", userId, e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to track shipment: " + e.getMessage())
                .errorCode("TRACK_SHIPMENT_FAILED")
                .build();
        }
    }
}
