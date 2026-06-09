package com.ai.fabric.realapps.chat.returns.action;

import com.ai.fabric.realapps.chat.returns.domain.ReturnRequest;
import com.ai.fabric.realapps.chat.returns.service.ReturnRequestService;
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

@AIAction(
    name = "create_return_request",
    description = "Create a return request (RMA) for an order (by order number or id)",
    category = "commerce",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
@RequiredArgsConstructor
@Slf4j
public class CreateReturnRequestActionHandler {

    private final ReturnRequestService returnRequestService;

    @ActionConfirmation
    public String confirm() {
        return "Create a return request for this order?";
    }

    @ActionExecute
    public ActionResult execute(
        @Param(value = "orderNumber", description = "Order number (PO-...) or order id", required = true) String orderNumber,
        @Param(value = "reason", description = "Reason for return", required = true) String reason,
        ActionContext context
    ) {
        String userId = context != null ? context.userId() : null;
        try {
            ReturnRequest created = returnRequestService.create(userId, orderNumber, reason);
            String returnId = created != null && created.getId() != null ? String.valueOf(created.getId()) : null;
            ActionTargetRef returnTarget = returnId != null
                ? new ActionTargetRef(returnId, "return_request", "return request", Map.of("returnRequestId", returnId))
                : null;
            return ActionResult.builder()
                .success(true)
                .message(created.isEligible() ? "Return request created" : "Return request created (not eligible)")
                .data(ActionResultContracts.object(Map.of(
                    "returnRequestId", created.getId(),
                    "status", created.getStatus() != null ? created.getStatus().name() : null,
                    "eligible", created.isEligible()
                )))
                .pinnedTargets(returnTarget != null ? List.of(returnTarget) : null)
                .build();
        } catch (Exception e) {
            log.error("Create return request failed for user {}", userId, e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to create return request: " + e.getMessage())
                .errorCode("CREATE_RETURN_REQUEST_FAILED")
                .build();
        }
    }
}
