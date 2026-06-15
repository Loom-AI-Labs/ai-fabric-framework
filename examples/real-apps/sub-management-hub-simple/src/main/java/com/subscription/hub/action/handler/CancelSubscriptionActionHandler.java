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
import com.subscription.hub.service.SubscriptionService;
import com.subscription.hub.service.UserService;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.UUID;

@AIAction(
    name = "cancel_subscription",
    description = "Cancel an active subscription",
    category = "subscription",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
@Slf4j
public class CancelSubscriptionActionHandler extends BaseActionHandler {

    private final SubscriptionService subscriptionService;

    public CancelSubscriptionActionHandler(SubscriptionService subscriptionService, UserService userService) {
        super(userService);
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
        } catch (Exception e) {
            return false;
        }
    }

    @ActionConfirmation
    public String confirm(@Param(value = "subscriptionId", description = "UUID of the subscription to cancel") String subscriptionId) {
        if (subscriptionId == null) {
            return "Are you sure you want to cancel your subscription? This action cannot be undone.";
        }

        try {
            var subscription = subscriptionService.findById(UUID.fromString(subscriptionId));
            return String.format(
                "Are you sure you want to cancel your subscription? " +
                "Your access will end on %s. This action cannot be undone.",
                subscription.getEndDate() != null ? subscription.getEndDate().toString() : "immediately"
            );
        } catch (Exception e) {
            return "Are you sure you want to cancel your subscription? This action cannot be undone.";
        }
    }

    @ActionExecute
    public ActionResult execute(
        @Param(value = "subscriptionId", required = true, description = "UUID of the subscription to cancel") String subscriptionId,
        @Param(value = "reason", description = "Optional reason for cancellation") String reason,
        ActionContext context
    ) {
        String userId = context != null ? context.userId() : null;
        try {
            String safeReason = reason != null && !reason.isBlank() ? reason : "User requested";

            var subscription = subscriptionService.unsubscribe(
                UUID.fromString(subscriptionId),
                safeReason
            );

            return ActionResult.builder()
                .success(true)
                .message("Your subscription has been cancelled successfully")
                .data(ActionResultContracts.object(Map.of(
                    "subscriptionId", subscriptionId,
                    "status", subscription.getStatus().toString(),
                    "endDate", subscription.getEndDate() != null ? subscription.getEndDate().toString() : ""
                )))
                .build();
        } catch (Exception e) {
            log.error("Error cancelling subscription", e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to cancel subscription. Please contact support.")
                .errorCode("CANCEL_FAILED")
                .build();
        }
    }
}
