package com.subscription.hub.action.handler;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionAllowed;
import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.intent.action.annotation.Param;
import com.subscription.hub.entity.Subscription;
import com.subscription.hub.service.AccountResolutionService;
import com.subscription.hub.service.SubscriptionService;
import com.subscription.hub.service.UserService;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@AIAction(
    name = "inspect_account_readiness",
    description = "Inspect account blockers and policies that explain whether the user can continue using the app or place an order",
    category = "account-resolver",
    accessMode = ActionAccessMode.READ,
    requiresConfirmation = false,
    readActionResolutionEligible = true
)
@Slf4j
public class InspectAccountReadinessActionHandler extends BaseActionHandler {

    private final AccountResolutionService accountResolutionService;
    private final SubscriptionService subscriptionService;

    public InspectAccountReadinessActionHandler(AccountResolutionService accountResolutionService,
                                                SubscriptionService subscriptionService,
                                                UserService userService) {
        super(userService);
        this.accountResolutionService = accountResolutionService;
        this.subscriptionService = subscriptionService;
    }

    @ActionAllowed
    public boolean allowed(ActionContext context) {
        String userId = context != null ? context.userId() : null;
        return userId != null && !userId.isBlank();
    }

    @ActionExecute
    public ActionResult execute(
        @Param(value = "userId", description = "Numeric demo user ID") String userId,
        @Param(value = "subscriptionId", description = "UUID of the subscription") String subscriptionId,
        ActionContext context
    ) {
        try {
            AccountResolutionService.AccountReadiness readiness = resolveReadiness(userId, subscriptionId, context);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("readiness", readiness);
            data.put("canContinue", readiness.canContinue());
            data.put("blockers", readiness.blockers());
            data.put("recommendedActions", readiness.recommendedActions());

            return ActionResult.builder()
                .success(true)
                .message(readiness.canContinue()
                    ? "Account is ready to continue"
                    : "Account has blockers that need resolution")
                .data(ActionResultContracts.object(data))
                .build();
        } catch (Exception ex) {
            log.error("Error inspecting account readiness", ex);
            return ActionResult.builder()
                .success(false)
                .message("Failed to inspect account readiness. " + ex.getMessage())
                .errorCode("ACCOUNT_READINESS_FAILED")
                .build();
        }
    }

    private AccountResolutionService.AccountReadiness resolveReadiness(String userId,
                                                                       String subscriptionId,
                                                                       ActionContext context) {
        if (subscriptionId != null && !subscriptionId.isBlank()) {
            return accountResolutionService.inspectReadiness(UUID.fromString(subscriptionId));
        }

        String effectiveUserId = userId != null && !userId.isBlank()
            ? userId
            : context != null ? context.userId() : null;
        if (effectiveUserId == null || effectiveUserId.isBlank()) {
            throw new IllegalArgumentException("userId or subscriptionId is required");
        }

        try {
            return accountResolutionService.inspectReadiness(Long.parseLong(effectiveUserId));
        } catch (NumberFormatException ignored) {
            UUID userUuid = UUID.fromString(effectiveUserId);
            Subscription subscription = subscriptionService.getActiveSubscription(userUuid)
                .orElseThrow(() -> new IllegalArgumentException("Active subscription not found for user"));
            return accountResolutionService.inspectReadiness(subscription.getId());
        }
    }
}
