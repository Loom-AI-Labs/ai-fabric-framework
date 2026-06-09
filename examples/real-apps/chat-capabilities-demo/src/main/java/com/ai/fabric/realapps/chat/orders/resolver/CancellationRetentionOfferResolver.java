package com.ai.fabric.realapps.chat.orders.resolver;

import com.ai.fabric.realapps.chat.orders.action.OfferOrderDiscountActionHandler;
import com.ai.infrastructure.chat.annotation.AIConfirmationInterceptors;
import com.ai.infrastructure.chat.annotation.OnPendingActionConfirmation;
import com.ai.infrastructure.chat.interception.ConfirmationInterceptionContext;
import com.ai.infrastructure.chat.interception.InterceptionDecision;
import com.ai.infrastructure.dto.IntentType;
import com.ai.infrastructure.intent.action.PendingAction;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * App-level confirmation interception example:
 * when user confirms cancelling an order, offer a retention discount first.
 *
 * <p>This is intentionally app-specific policy (not a framework default).</p>
 */
@AIConfirmationInterceptors
public class CancellationRetentionOfferResolver {

    private static final String INTERCEPT_ACTION = "cancel_purchase_order";
    private static final String OFFER_ACTION = OfferOrderDiscountActionHandler.ACTION_NAME;
    private static final String PARAM_RETENTION_OFFERED = "_retentionOfferOffered";

    @OnPendingActionConfirmation(
        pendingActions = {INTERCEPT_ACTION},
        confirmation = IntentType.CONFIRMATION_POSITIVE,
        onceParam = PARAM_RETENTION_OFFERED
    )
    public InterceptionDecision offerDiscount(ConfirmationInterceptionContext ctx) {
        return ctx.promptAction(OFFER_ACTION, buildOfferParams(ctx.pending()));
    }

    @OnPendingActionConfirmation(
        pendingActions = {OFFER_ACTION},
        confirmation = IntentType.CONFIRMATION_POSITIVE
    )
    public InterceptionDecision acceptOffer(ConfirmationInterceptionContext ctx) {
        PendingAction offer = ctx.popPending();
        if (offer == null) {
            return ctx.reply("There is no pending action to confirm.");
        }

        PendingAction previous = ctx.peekPending();
        if (previous != null && INTERCEPT_ACTION.equalsIgnoreCase(previous.action())) {
            ctx.popPending();
        }

        Map<String, Object> params = offer.actionParams() != null ? offer.actionParams() : Map.of();
        return ctx.executeAction(OFFER_ACTION, params);
    }

    @OnPendingActionConfirmation(
        pendingActions = {OFFER_ACTION},
        confirmation = IntentType.CONFIRMATION_NEGATIVE
    )
    public InterceptionDecision rejectOffer(ConfirmationInterceptionContext ctx) {
        // Remove the offer.
        ctx.popPending();

        // If the previous pending action is the original cancellation, execute it now (already confirmed).
        PendingAction cancel = ctx.peekPending();
        if (cancel != null && INTERCEPT_ACTION.equalsIgnoreCase(cancel.action())) {
            ctx.popPending();
            Map<String, Object> params = cancel.actionParams() != null ? cancel.actionParams() : Map.of();
            return ctx.executeAction(cancel.action(), params);
        }

        return ctx.reply("Okay — I won't apply the discount. What would you like to do next?");
    }

    private Map<String, Object> buildOfferParams(PendingAction pendingAction) {
        Map<String, Object> params = pendingAction != null ? pendingAction.actionParams() : null;
        Map<String, Object> offerParams = new LinkedHashMap<>();
        if (params != null) {
            if (params.get("orderNumber") != null && StringUtils.hasText(String.valueOf(params.get("orderNumber")))) {
                offerParams.put("orderNumber", params.get("orderNumber"));
            }
            if (params.get("orderId") != null) {
                offerParams.put("orderId", params.get("orderId"));
            }
        }
        offerParams.put("discountPercent", 10);
        return offerParams;
    }
}
