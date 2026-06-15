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
    name = "upgrade_subscription",
    description = "Upgrade subscription to a higher tier plan",
    category = "subscription",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
@Slf4j
public class UpgradeSubscriptionActionHandler extends BaseActionHandler {

    private final SubscriptionService subscriptionService;

    public UpgradeSubscriptionActionHandler(SubscriptionService subscriptionService, UserService userService) {
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
    public String confirm(
        @Param(value = "subscriptionId", required = true, description = "UUID of the subscription to upgrade") String subscriptionId,
        @Param(value = "newPlanId", required = true, description = "UUID of the new plan to upgrade to") String newPlanId
    ) {
        return "Are you sure you want to upgrade to this plan? Your billing will be updated accordingly.";
    }

    @ActionExecute
    public ActionResult execute(
        @Param(value = "subscriptionId", required = true, description = "UUID of the subscription to upgrade") String subscriptionId,
        @Param(value = "newPlanId", required = true, description = "UUID of the new plan to upgrade to") String newPlanId,
        ActionContext context
    ) {
        String userId = context != null ? context.userId() : null;
        try {
            var subscription = subscriptionService.upgrade(
                UUID.fromString(subscriptionId),
                UUID.fromString(newPlanId)
            );

            return ActionResult.builder()
                .success(true)
                .message("Your subscription has been upgraded successfully!")
                .data(ActionResultContracts.object(Map.of(
                    "subscriptionId", subscriptionId,
                    "newPlanId", newPlanId,
                    "status", subscription.getStatus().toString()
                )))
                .build();
        } catch (Exception e) {
            log.error("Error upgrading subscription", e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to upgrade subscription. " + e.getMessage())
                .errorCode("UPGRADE_FAILED")
                .build();
        }
    }
}
