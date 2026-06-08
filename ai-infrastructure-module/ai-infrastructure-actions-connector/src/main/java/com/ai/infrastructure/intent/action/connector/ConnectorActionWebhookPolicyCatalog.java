package com.ai.infrastructure.intent.action.connector;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ConnectorActionWebhookPolicyCatalog {

    private final ConnectorActionCatalogService catalogService;

    public ConnectorActionWebhookPolicyCatalog(ConnectorActionCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    public List<ConnectorActionPostPolicyDefinition> postPoliciesForAction(String actionName) {
        if (!StringUtils.hasText(actionName) || catalogService == null) {
            return List.of();
        }
        return catalogService.getCatalog().actions().stream()
            .filter(action -> action != null && StringUtils.hasText(action.name()))
            .filter(action -> action.name().trim().equalsIgnoreCase(actionName.trim()))
            .findFirst()
            .map(ConnectorActionDefinition::postPolicies)
            .orElse(List.of());
    }

    public Map<String, ConnectorWebhookTargetDefinition> webhookTargetsById() {
        if (catalogService == null) {
            return Map.of();
        }
        return catalogService.getCatalog().webhookTargets().stream()
            .filter(target -> target != null && StringUtils.hasText(target.id()))
            .collect(Collectors.toMap(
                target -> target.id().trim().toLowerCase(Locale.ROOT),
                Function.identity(),
                (left, right) -> right
            ));
    }

    public List<ConnectorWebhookTargetDefinition> webhookTargets() {
        return catalogService != null ? catalogService.getCatalog().webhookTargets() : List.of();
    }

    public long postPolicyCount() {
        if (catalogService == null) {
            return 0L;
        }
        return catalogService.getCatalog().actions().stream()
            .filter(action -> action != null && action.postPolicies() != null)
            .mapToLong(action -> action.postPolicies().size())
            .sum();
    }

    public Set<String> actionNamesWithPostPolicies() {
        if (catalogService == null) {
            return Set.of();
        }
        return catalogService.getCatalog().actions().stream()
            .filter(action -> action != null
                && StringUtils.hasText(action.name())
                && action.postPolicies() != null
                && !action.postPolicies().isEmpty())
            .map(action -> action.name().trim())
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
