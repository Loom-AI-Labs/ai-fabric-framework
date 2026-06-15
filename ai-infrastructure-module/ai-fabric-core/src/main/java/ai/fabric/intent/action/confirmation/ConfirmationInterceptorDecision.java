package ai.fabric.intent.action.confirmation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ConfirmationInterceptorDecision(
    ConfirmationInterceptorDecisionType type,
    String action,
    Map<String, Object> params,
    String message
) {
    public ConfirmationInterceptorDecision {
        params = params != null
            ? Collections.unmodifiableMap(new LinkedHashMap<>(params))
            : Map.of();
    }
}
