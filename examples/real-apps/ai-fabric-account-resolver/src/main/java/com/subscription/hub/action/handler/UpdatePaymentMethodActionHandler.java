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
import com.subscription.hub.entity.PaymentMethod;
import com.subscription.hub.entity.Subscription;
import com.subscription.hub.service.AccountResolutionService;
import com.subscription.hub.service.SubscriptionService;
import com.subscription.hub.service.UserService;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@AIAction(
    name = "update_payment_method",
    description = "Add or replace the verified payment method needed to unblock account usage and ordering",
    category = "account-resolver",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
@Slf4j
public class UpdatePaymentMethodActionHandler extends BaseActionHandler {

    private final AccountResolutionService accountResolutionService;
    private final SubscriptionService subscriptionService;

    public UpdatePaymentMethodActionHandler(AccountResolutionService accountResolutionService,
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
        @Param(value = "provider", description = "Payment provider or stored payment method label") String provider,
        @Param(value = "last4", description = "Last four digits of the stored payment method") String last4
    ) {
        String brand = provider != null && !provider.isBlank() ? provider.trim() : "stored card";
        String safeLast4 = last4 != null && !last4.isBlank() ? last4.trim() : "provided digits";
        return "Use " + brand + " ending in " + safeLast4 + " as the verified payment method for this account?";
    }

    @ActionExecute
    public ActionResult execute(
        @Param(value = "subscriptionId", description = "Resolved active subscription for the current user") String subscriptionId,
        @Param(value = "type", description = "Payment method type inferred by the account resolver", allowedValues = {"CARD", "BANK_TRANSFER", "PAYPAL"}) PaymentMethod.PaymentType type,
        @Param(value = "provider", description = "Payment provider or stored payment method label inferred by the account resolver") String provider,
        @Param(value = "last4", required = true, description = "Last four digits of the stored payment method to use", pattern = ".*\\d{4}.*") String last4,
        ActionContext context
    ) {
        try {
            UUID resolvedSubscriptionId = resolveSubscriptionId(subscriptionId, context);
            AccountResolutionService.PaymentMethodResult result = accountResolutionService.updatePaymentMethod(
                resolvedSubscriptionId,
                type,
                provider,
                last4
            );
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("subscriptionId", result.subscriptionId());
            data.put("paymentType", result.paymentType());
            data.put("provider", result.provider());
            data.put("last4", result.last4());
            data.put("verified", result.verified());
            data.put("readiness", result.readiness());

            return ActionResult.builder()
                .success(true)
                .message(result.readiness().canContinue()
                    ? "Payment method updated and the account is ready to continue"
                    : "Payment method updated")
                .data(ActionResultContracts.object(data))
                .build();
        } catch (Exception ex) {
            log.error("Error updating payment method", ex);
            return ActionResult.builder()
                .success(false)
                .message("Failed to update payment method. " + ex.getMessage())
                .errorCode("UPDATE_PAYMENT_METHOD_FAILED")
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
