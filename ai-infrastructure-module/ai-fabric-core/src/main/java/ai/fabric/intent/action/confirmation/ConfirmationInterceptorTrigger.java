package ai.fabric.intent.action.confirmation;

import ai.fabric.dto.IntentType;
import java.util.List;

public record ConfirmationInterceptorTrigger(
    List<String> pendingActions,
    IntentType confirmation,
    String onceParam
) {
    public ConfirmationInterceptorTrigger {
        pendingActions = pendingActions != null ? List.copyOf(pendingActions) : List.of();
    }
}
