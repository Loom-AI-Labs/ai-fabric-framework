package ai.fabric.chat.it.actions;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionAllowed;
import ai.fabric.intent.action.annotation.ActionConfirmation;
import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.intent.action.annotation.Param;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.util.StringUtils;

@AIAction(
    name = ConfirmableTransferActionHandler.ACTION_NAME,
    description = "Test-only transfer action with parameters collected over multiple turns.",
    category = "test",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
public class ConfirmableTransferActionHandler {

    public static final String ACTION_NAME = "confirmable_transfer";

    private static final AtomicInteger EXECUTION_COUNT = new AtomicInteger();
    private static volatile BigDecimal lastAmount;
    private static volatile String lastReason;

    @ActionAllowed
    public boolean allowed(ActionContext context) {
        return context != null && StringUtils.hasText(context.identifier());
    }

    @ActionConfirmation
    public String confirm(
        @Param(
            value = "amount",
            required = true,
            description = "Transfer amount"
        ) BigDecimal amount,
        @Param(
            value = "reason",
            required = true,
            description = "Transfer reason"
        ) String reason
    ) {
        return "Transfer " + amount + " for " + reason + "?";
    }

    @ActionExecute
    public ActionResult execute(
        @Param(
            value = "amount",
            required = true,
            description = "Transfer amount"
        ) BigDecimal amount,
        @Param(
            value = "reason",
            required = true,
            description = "Transfer reason"
        ) String reason
    ) {
        EXECUTION_COUNT.incrementAndGet();
        lastAmount = amount;
        lastReason = reason;
        return ActionResult.builder()
            .success(true)
            .message("Transfer recorded")
            .data(ActionResultContracts.object(Map.of(
                "amount", amount,
                "reason", reason
            )))
            .build();
    }

    public static void resetExecutions() {
        EXECUTION_COUNT.set(0);
        lastAmount = null;
        lastReason = null;
    }

    public static int getExecutionCount() {
        return EXECUTION_COUNT.get();
    }

    public static BigDecimal getLastAmount() {
        return lastAmount;
    }

    public static String getLastReason() {
        return lastReason;
    }
}
