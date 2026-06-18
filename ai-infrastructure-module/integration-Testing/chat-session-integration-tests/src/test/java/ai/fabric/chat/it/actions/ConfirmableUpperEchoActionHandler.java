package ai.fabric.chat.it.actions;

import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionAllowed;
import ai.fabric.intent.action.annotation.ActionConfirmation;
import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.intent.action.annotation.Param;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.util.StringUtils;

@AIAction(
    name = ConfirmableUpperEchoActionHandler.ACTION_NAME,
    description = "Test-only action that requires confirmation and uppercases the message.",
    category = "test",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
public class ConfirmableUpperEchoActionHandler {

    public static final String ACTION_NAME = "confirmable_upper_echo";

    private static final AtomicInteger EXECUTION_COUNT = new AtomicInteger(0);

    @ActionAllowed
    public boolean allowed(ActionContext context) {
        return context != null && StringUtils.hasText(context.identifier());
    }

    @ActionConfirmation
    public String confirm(@Param(value = "message", required = true, description = "Text to uppercase") String message) {
        return "Confirm execute confirmable_upper_echo(message=" + message + ")?";
    }

    @ActionExecute
    public ActionResult execute(@Param(value = "message", required = true, description = "Text to uppercase") String message) {
        EXECUTION_COUNT.incrementAndGet();
        ConfirmableActionExecutionLog.record(ACTION_NAME);

        String upper = StringUtils.hasText(message) ? message.toUpperCase(Locale.ROOT) : "OK";
        return ActionResult.builder()
            .success(true)
            .message("Upper: " + upper)
            .data(ActionResultContracts.object(Map.of("upper", upper)))
            .build();
    }

    public static void resetExecutions() {
        EXECUTION_COUNT.set(0);
        ConfirmableActionExecutionLog.reset();
    }

    public static int getExecutionCount() {
        return EXECUTION_COUNT.get();
    }

    public static java.util.List<String> getExecutionOrder() {
        return ConfirmableActionExecutionLog.getExecutions();
    }
}
