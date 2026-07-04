package com.subscription.hub.action.handler;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionAllowed;
import ai.fabric.intent.action.annotation.ActionExecute;
import com.subscription.hub.service.AccountResolutionService;
import com.subscription.hub.service.UserService;
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
