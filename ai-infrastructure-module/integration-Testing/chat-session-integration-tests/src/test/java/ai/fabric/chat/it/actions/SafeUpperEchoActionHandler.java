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

import java.util.Locale;
import java.util.Map;

@AIAction(
    name = SafeUpperEchoActionHandler.ACTION_NAME,
    description = "Test-only safe action that echoes an upper-cased message. No side effects.",
    category = "test",
    accessMode = ActionAccessMode.READ,
    requiresConfirmation = false
)
public class SafeUpperEchoActionHandler {

    public static final String ACTION_NAME = "safe_upper_echo";

    @ActionAllowed
    public boolean allowed(ActionContext context) {
        return context != null && StringUtils.hasText(context.identifier());
    }

    @ActionExecute
    public ActionResult execute(@Param(value = "message", description = "Text to echo back upper-cased") String message) {
        String echoed = StringUtils.hasText(message) ? message.toUpperCase(Locale.ROOT) : "OK";
        return ActionResult.builder()
            .success(true)
            .message("Upper Echo: " + echoed)
            .data(ActionResultContracts.object(Map.of("echo", echoed)))
            .build();
    }
}
