package com.ai.infrastructure.intent.action;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Aggregates all declared {@link AIActionProvider} implementations and exposes a consolidated view.
 */
@Slf4j
@Service
public class AvailableActionsRegistry {

    private final List<AIActionProvider> providers;

    public AvailableActionsRegistry(List<AIActionProvider> providers) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

    /**
     * Returns a deterministic, de-duplicated list of all known actions.
     */
    public List<ActionInfo> getAllAvailableActions() {
        if (providers.isEmpty()) {
            return List.of();
        }

        Map<String, ActionInfo> deduplicated = new LinkedHashMap<>();
        for (AIActionProvider provider : providers) {
            List<ActionInfo> actions = safeActions(provider);
            for (ActionInfo action : actions) {
                if (action == null) {
                    continue;
                }
                if (!action.hasValidName()) {
                    log.warn("Skipping action without name from provider {}", provider.getProviderName());
                    continue;
                }
                String key = action.getName().trim().toLowerCase(Locale.ROOT);
                if (deduplicated.containsKey(key)) {
                    log.debug("Duplicate action '{}' encountered from provider {}; keeping first declaration",
                        action.getName(), provider.getProviderName());
                    continue;
                }
                deduplicated.put(key, action.sanitizedCopy());
            }
        }

        return List.copyOf(deduplicated.values());
    }

    /**
     * Finds an action by name (case insensitive).
     */
    public Optional<ActionInfo> findByName(String actionName) {
        if (actionName == null || actionName.isBlank()) {
            return Optional.empty();
        }
        String normalized = actionName.trim().toLowerCase(Locale.ROOT);
        return getAllAvailableActions().stream()
            .filter(action -> action.getName().equalsIgnoreCase(normalized))
            .findFirst();
    }

    /**
     * Returns actions grouped by category preserving declaration order.
     */
    public Map<String, List<ActionInfo>> getActionsByCategory() {
        List<ActionInfo> actions = getAllAvailableActions();
        Map<String, List<ActionInfo>> grouped = new LinkedHashMap<>();
        for (ActionInfo action : actions) {
            String category = action.getCategory() == null ? "default" : action.getCategory().toLowerCase(Locale.ROOT);
            grouped.computeIfAbsent(category, key -> new ArrayList<>()).add(action);
        }
        grouped.replaceAll((key, value) -> Collections.unmodifiableList(value));
        return Collections.unmodifiableMap(grouped);
    }

    /**
     * Generates a diagnostic summary of registered providers and action counts.
     */
    public String describeRegistry() {
        if (providers.isEmpty()) {
            return "AvailableActionsRegistry[providers=0, actions=0]";
        }
        String summary = providers.stream()
            .map(provider -> provider.getProviderName() + ":" + safeActions(provider).size())
            .collect(Collectors.joining(", "));
        return "AvailableActionsRegistry[" + summary + "]";
    }

    private List<ActionInfo> safeActions(AIActionProvider provider) {
        try {
            List<ActionInfo> actions = provider.getAvailableActions();
            if (actions == null || actions.isEmpty()) {
                return List.of();
            }
            return actions;
        } catch (Exception ex) {
            log.error("Action provider {} threw an exception while listing actions", provider.getProviderName(), ex);
            return List.of();
        }
    }
}