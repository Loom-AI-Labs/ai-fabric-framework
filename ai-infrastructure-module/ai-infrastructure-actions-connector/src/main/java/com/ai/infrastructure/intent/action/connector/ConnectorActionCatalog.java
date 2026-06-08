package com.ai.infrastructure.intent.action.connector;

import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorRule;
import java.util.List;

public record ConnectorActionCatalog(
    List<ConnectorActionDefinition> actions,
    List<ConfirmationInterceptorRule> confirmationInterceptors,
    List<ConnectorWebhookTargetDefinition> webhookTargets,
    List<String> sourceLocations
) {
    public ConnectorActionCatalog {
        actions = actions != null ? List.copyOf(actions) : List.of();
        confirmationInterceptors = confirmationInterceptors != null ? List.copyOf(confirmationInterceptors) : List.of();
        webhookTargets = webhookTargets != null ? List.copyOf(webhookTargets) : List.of();
        sourceLocations = sourceLocations != null ? List.copyOf(sourceLocations) : List.of();
    }
}
