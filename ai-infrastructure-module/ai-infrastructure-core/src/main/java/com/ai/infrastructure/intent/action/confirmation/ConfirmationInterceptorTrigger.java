package com.ai.infrastructure.intent.action.confirmation;

import com.ai.infrastructure.dto.IntentType;
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
