package com.ai.fabric.examples.governedactions.action;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionExecute;

import java.util.Map;

@AIAction(
     name ="get_account",
     description = "Get the current user's account information",
     category = "account",
     accessMode = ActionAccessMode.READ,
     requiresConfirmation = false
)
public class GetAccountAction {

    @ActionExecute
    public ActionResult execute(){
        return ActionResult.builder()
                .success(true)
                .message("Account retrieved")
                .data(ActionResultContracts.object(
                        Map.of(
                                "name", "Demo User",
                                "email", "demo@example.com"
                        )
                )).build();
    }

}
