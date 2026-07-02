package com.subscription.hub.action.handler;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionAllowed;
import ai.fabric.intent.action.annotation.ActionConfirmation;
import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.intent.action.annotation.Param;
import com.subscription.hub.entity.RefundRequest;
import com.subscription.hub.entity.Subscription;
import com.subscription.hub.service.AccountResolutionService;
import com.subscription.hub.service.SubscriptionService;
import com.subscription.hub.service.UserService;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@AIAction(
    name = "request_refund",
    description = "Create a governed refund or account credit resolution for a subscription billing issue",
    category = "account-resolver",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
@Slf4j
public class RequestRefundActionHandler extends BaseActionHandler {

    private final AccountResolutionService accountResolutionService;
    private final SubscriptionService subscriptionService;

    public RequestRefundActionHandler(AccountResolutionService accountResolutionService,
                                      SubscriptionService subscriptionService,
                                      UserService userService) {
        super(userService);
        this.accountResolutionService = accountResolutionService;
        this.subscriptionService = subscriptionService;
    }

    @ActionAllowed
    public boolean allowed(ActionContext context) {
        String userId = context != null ? context.userId() : null;
        if (userId == null || userId.isBlank()) {
            return false;
        }
        try {
            UUID userUuid = parseUserId(userId);
            return subscriptionService.hasActiveSubscription(userUuid);
        } catch (Exception ex) {
            return false;
        }
    }

    @ActionConfirmation
    public String confirm(
        @Param(value = "amount", description = "Refund or account credit amount") BigDecimal amount,
        @Param(value = "resolutionType", description = "REFUND or ACCOUNT_CREDIT") RefundRequest.ResolutionType resolutionType
    ) {
        RefundRequest.ResolutionType type = resolutionType != null
            ? resolutionType
            : RefundRequest.ResolutionType.ACCOUNT_CREDIT;
        String amountText = amount != null ? "$" + amount : "the requested amount";
        return "Create a " + type.name().toLowerCase().replace('_', ' ') + " for " + amountText + "?";
    }

    @ActionExecute
    public ActionResult execute(
        @Param(value = "subscriptionId", description = "UUID of the subscription") String subscriptionId,
        @Param(value = "amount", required = true, description = "Refund or account credit amount", min = 1) BigDecimal amount,
        @Param(value = "reason", description = "Reason for the billing resolution") String reason,
        @Param(value = "resolutionType", description = "REFUND or ACCOUNT_CREDIT", allowedValues = {"REFUND", "ACCOUNT_CREDIT"}) RefundRequest.ResolutionType resolutionType,
        ActionContext context
    ) {
        try {
            UUID resolvedSubscriptionId = resolveSubscriptionId(subscriptionId, context);
            AccountResolutionService.RefundResolutionResult result = accountResolutionService.requestRefund(
                resolvedSubscriptionId,
                amount,
                reason,
                resolutionType
            );
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("refundRequestId", result.refundRequestId());
            data.put("subscriptionId", result.subscriptionId());
            data.put("resolutionType", result.resolutionType());
            data.put("status", result.status());
            data.put("amount", result.amount());
            data.put("reason", result.reason());
            data.put("createdAt", result.createdAt());

            return ActionResult.builder()
                .success(true)
                .message("Billing resolution created with status " + result.status())
                .data(ActionResultContracts.object(data))
                .build();
        } catch (Exception ex) {
            log.error("Error requesting refund", ex);
            return ActionResult.builder()
                .success(false)
                .message("Failed to create billing resolution. " + ex.getMessage())
                .errorCode("REQUEST_REFUND_FAILED")
                .build();
        }
    }

    private UUID resolveSubscriptionId(String subscriptionId, ActionContext context) {
        if (subscriptionId != null && !subscriptionId.isBlank()) {
            return UUID.fromString(subscriptionId);
        }
        String userId = context != null ? context.userId() : null;
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("subscriptionId or userId is required");
        }
        UUID userUuid = parseUserId(userId);
        return subscriptionService.getActiveSubscription(userUuid)
            .map(Subscription::getId)
            .orElseThrow(() -> new IllegalArgumentException("Active subscription not found for user"));
    }
}
