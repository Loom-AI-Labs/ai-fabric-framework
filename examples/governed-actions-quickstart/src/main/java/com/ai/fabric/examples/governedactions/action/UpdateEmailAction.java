package com.ai.fabric.examples.governedactions.action;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionConfirmation;
import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.intent.action.annotation.Param;

@AIAction(
        name = "update_email",
        description = "Update the email of the current user",
        category = "account",
        accessMode = ActionAccessMode.WRITE_ONLY,
        requiresConfirmation = true
)
public class UpdateEmailAction {

    private static final String EMAIL_PATTERN =
            "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$";

    @ActionExecute
    public ActionResult execute(
            @Param(
                    value = "email",
                    required = true,
                    description = "The new email adress",
                    pattern = EMAIL_PATTERN
            ) String email
    ){
        return ActionResult.builder()
                .success(true)
                .message("Email updated to " + email)
                .build();
    }


    @ActionConfirmation
    public String confirm(
            @Param(
                    value = "email",
                    required = true,
                    description = "The new email adress",
                    pattern = EMAIL_PATTERN
            )
            String email
    ){
        return "Change your email to " + email + "?";
    }
}
