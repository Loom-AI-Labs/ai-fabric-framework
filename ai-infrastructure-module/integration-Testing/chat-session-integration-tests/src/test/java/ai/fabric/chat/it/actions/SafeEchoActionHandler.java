package ai.fabric.chat.it.actions;

import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionAllowed;
import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.intent.action.annotation.Param;
import org.springframework.util.StringUtils;

import java.util.Map;

@AIAction(
    name = SafeEchoActionHandler.ACTION_NAME,
    description = "Test-only safe action that echoes a message. No side effects.",
    category = "test",
    accessMode = ActionAccessMode.READ,
    requiresConfirmation = false
)
public class SafeEchoActionHandler {

    public static final String ACTION_NAME = "safe_echo";

    @ActionAllowed
    public boolean allowed(ActionContext context) {
        return context != null && StringUtils.hasText(context.identifier());
    }

    @ActionExecute
    public ActionResult execute(@Param(value = "message", description = "Text to echo back") String message) {
        String echoed = StringUtils.hasText(message) ? message : "ok";
        return ActionResult.builder()
            .success(true)
            .message("Echo: " + echoed)
            .data(ActionResultContracts.object(Map.of("echo", echoed)))
            .build();
    }
}
