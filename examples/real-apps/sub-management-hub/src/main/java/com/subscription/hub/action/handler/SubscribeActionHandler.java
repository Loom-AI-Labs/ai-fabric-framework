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
import com.subscription.hub.entity.Subscription;
import com.subscription.hub.service.SubscriptionService;
import com.subscription.hub.service.UserService;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.UUID;

@AIAction(
    name = "subscribe",
    description = "Subscribe to a subscription plan",
    category = "subscription",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
@Slf4j
public class SubscribeActionHandler extends BaseActionHandler {

    private final SubscriptionService subscriptionService;

    public SubscribeActionHandler(SubscriptionService subscriptionService, UserService userService) {
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
            // User can subscribe if they don't have an active subscription
            return !subscriptionService.hasActiveSubscription(userUuid);
        } catch (Exception e) {
            return false;
        }
    }

    @ActionConfirmation
    public String confirm(
        @Param(value = "planId", required = true, description = "UUID of the plan to subscribe to") String planId,
        @Param(value = "billingCycle", description = "MONTHLY or ANNUAL") String billingCycle
    ) {
        String cycle = billingCycle != null && !billingCycle.isBlank() ? billingCycle : "MONTHLY";
        return String.format(
            "Are you sure you want to subscribe to this plan with %s billing?",
            cycle
        );
    }

    @ActionExecute
    public ActionResult execute(
        @Param(value = "planId", required = true, description = "UUID of the plan to subscribe to") String planId,
        @Param(value = "billingCycle", description = "MONTHLY or ANNUAL") String billingCycle,
        ActionContext context
    ) {
        String userId = context != null ? context.userId() : null;
        try {
            String cycle = billingCycle != null && !billingCycle.isBlank() ? billingCycle : "MONTHLY";
            Subscription.BillingCycle parsed = Subscription.BillingCycle.valueOf(cycle.toUpperCase());

            UUID userUuid = parseUserId(userId);
            Subscription subscription = subscriptionService.subscribe(
                userUuid,
                UUID.fromString(planId),
                parsed
            );

            return ActionResult.builder()
                .success(true)
                .message("You have successfully subscribed!")
                .data(ActionResultContracts.object(Map.of(
                    "subscriptionId", subscription.getId().toString(),
                    "planId", planId,
                    "status", subscription.getStatus().toString()
                )))
                .build();
        } catch (Exception e) {
            log.error("Error subscribing to plan", e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to subscribe. " + e.getMessage())
                .errorCode("SUBSCRIBE_FAILED")
                .build();
        }
    }
}
