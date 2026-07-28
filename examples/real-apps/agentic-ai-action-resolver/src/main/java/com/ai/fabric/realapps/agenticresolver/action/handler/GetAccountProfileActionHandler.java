package com.ai.fabric.realapps.agenticresolver.action.handler;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionAllowed;
import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.intent.action.annotation.ActionFacts;
import com.ai.fabric.realapps.agenticresolver.service.AccountResolutionService;
import com.ai.fabric.realapps.agenticresolver.service.UserService;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@AIAction(
    name = "get_account_profile",
    description = "Read factual current-account profile data for policy reasoning without returning blockers or recommendations",
    category = "account-resolver",
    accessMode = ActionAccessMode.READ,
    requiresConfirmation = false,
    readActionResolutionEligible = true
)
@Slf4j
public class GetAccountProfileActionHandler extends BaseActionHandler {

    private final AccountResolutionService accountResolutionService;

    public GetAccountProfileActionHandler(AccountResolutionService accountResolutionService,
                                          UserService userService) {
        super(userService);
        this.accountResolutionService = accountResolutionService;
    }

    @ActionAllowed
    public boolean allowed(ActionContext context) {
        String userId = context != null ? context.userId() : null;
        return userId != null && !userId.isBlank();
    }

    @ActionExecute
    public ActionResult execute(ActionContext context) {
        try {
            AccountResolutionService.AccountProfile profile = resolveProfile(context);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountProfile", profile);

            return ActionResult.builder()
                .success(true)
                .message("Account profile facts loaded for policy analysis")
                .data(ActionResultContracts.object(data))
                .build();
        } catch (Exception ex) {
            log.error("Error reading account profile", ex);
            return ActionResult.builder()
                .success(false)
                .message("Failed to read account profile. " + ex.getMessage())
                .errorCode("ACCOUNT_PROFILE_FAILED")
                .build();
        }
    }

    @ActionFacts
    public Map<String, Object> facts(ActionResult result, ActionContext context) {
        AccountResolutionService.AccountProfile profile = resolveProfile(context);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("factSource", "current_authenticated_account_profile");
        facts.put("subscriptionStatus", profile.subscription().status());
        facts.put("subscriptionActive", profile.subscription().active());
        facts.put("billingCycle", profile.subscription().billingCycle());
        facts.put("planName", profile.subscription().plan() != null ? profile.subscription().plan().name() : null);
        facts.put("planTier", profile.subscription().plan() != null ? profile.subscription().plan().tier() : null);
        facts.put("paymentMethodPresent", profile.paymentMethod().present());
        facts.put("paymentMethodVerified", profile.paymentMethod().verified());
        facts.put("billingAddressPresent", profile.billingAddress().present());
        facts.put("billingAddressValidated", profile.billingAddress().validated());
        facts.put("shippingAddressPresent", profile.shippingAddress().present());
        facts.put("shippingAddressValidated", profile.shippingAddress().validated());
        facts.put(
            "reasoningInstruction",
            "Infer account issues by comparing these facts with retrieved user-facing policies. Do not claim a requirement is missing when the corresponding fact is true."
        );
        return facts;
    }

    private AccountResolutionService.AccountProfile resolveProfile(ActionContext context) {
        String effectiveUserId = context != null ? context.userId() : null;
        if (effectiveUserId == null || effectiveUserId.isBlank()) {
            throw new IllegalArgumentException("authenticated user context is required");
        }

        try {
            return accountResolutionService.accountProfile(Long.parseLong(effectiveUserId));
        } catch (NumberFormatException ignored) {
            return accountResolutionService.accountProfile(UUID.fromString(effectiveUserId));
        }
    }
}
