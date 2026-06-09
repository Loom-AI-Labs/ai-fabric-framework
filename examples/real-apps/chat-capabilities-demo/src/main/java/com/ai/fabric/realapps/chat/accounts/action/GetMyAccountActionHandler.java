package com.ai.fabric.realapps.chat.accounts.action;

import com.ai.fabric.realapps.chat.accounts.domain.Account;
import com.ai.fabric.realapps.chat.accounts.service.AccountService;
import com.ai.infrastructure.intent.action.ActionAccessMode;
import com.ai.infrastructure.intent.action.ActionContext;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.action.ActionResultContracts;
import com.ai.infrastructure.intent.action.annotation.AIAction;
import com.ai.infrastructure.intent.action.annotation.ActionExecute;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AIAction(
    name = "get_my_account",
    description = "Get my account/profile details",
    category = "commerce",
    accessMode = ActionAccessMode.READ,
    requiresConfirmation = false
)
@RequiredArgsConstructor
@Slf4j
public class GetMyAccountActionHandler {

    private final AccountService accountService;

    @ActionExecute
    public ActionResult execute(ActionContext context) {
        String userId = context != null ? context.userId() : null;
        try {
            Account account = accountService.findByUserId(userId).orElse(null);
            if (account == null) {
                return ActionResult.builder()
                    .success(true)
                    .message("No account profile found")
                    .data(ActionResultContracts.object(Map.of("userId", userId)))
                    .build();
            }
            return ActionResult.builder()
                .success(true)
                .message("Account details")
                .data(ActionResultContracts.object(Map.of(
                    "accountId", account.getId(),
                    "userId", account.getUserId(),
                    "name", account.getName(),
                    "email", account.getEmail(),
                    "tier", account.getTier(),
                    "segment", account.getSegment()
                )))
                .build();
        } catch (Exception e) {
            log.error("Get my account failed for user {}", userId, e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to fetch account: " + e.getMessage())
                .errorCode("GET_MY_ACCOUNT_FAILED")
                .build();
        }
    }
}
