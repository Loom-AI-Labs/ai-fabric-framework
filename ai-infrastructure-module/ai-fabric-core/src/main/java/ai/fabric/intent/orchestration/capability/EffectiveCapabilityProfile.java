package ai.fabric.intent.orchestration.capability;

import ai.fabric.intent.action.AIActionNames;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Immutable server-authoritative capabilities for one orchestration execution.
 */
public record EffectiveCapabilityProfile(
    String profile,
    String mode,
    boolean retrievalEnabled,
    Set<String> effectiveVectorSpaces,
    Set<String> visibleActions,
    Set<String> executableReadActions,
    Set<String> proposableWriteActions,
    OrchestrationPolicy.RagBudgets ragBudgets,
    OrchestrationPolicy.ReadActionResolutionPolicy readActionResolutionPolicy,
    String profileHash
) {
    public EffectiveCapabilityProfile {
        profile = normalizeOptional(profile);
        mode = normalizeOptional(mode);
        effectiveVectorSpaces = immutableNames(effectiveVectorSpaces, false);
        visibleActions = immutableNames(visibleActions, true);
        executableReadActions = immutableNames(executableReadActions, true);
        proposableWriteActions = immutableNames(proposableWriteActions, true);
        ragBudgets = ragBudgets != null ? ragBudgets : OrchestrationPolicy.RagBudgets.defaults();
        readActionResolutionPolicy = readActionResolutionPolicy != null
            ? readActionResolutionPolicy
            : OrchestrationPolicy.ReadActionResolutionPolicy.defaults();
        profileHash = normalizeRequired(profileHash, "profileHash");
        if (!visibleActions.containsAll(executableReadActions)
            || !visibleActions.containsAll(proposableWriteActions)) {
            throw new IllegalArgumentException(
                "Executable/proposable actions must be visible in the effective profile"
            );
        }
    }

    public boolean isActionVisible(String actionName) {
        return contains(visibleActions, actionName);
    }

    public boolean canExecuteReadAction(String actionName) {
        return contains(executableReadActions, actionName);
    }

    public boolean canProposeWriteAction(String actionName) {
        return contains(proposableWriteActions, actionName);
    }

    private static boolean contains(Set<String> values, String actionName) {
        return actionName != null && values.contains(AIActionNames.normalize(actionName));
    }

    private static Set<String> immutableNames(Set<String> values, boolean actionNames) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            normalized.add(actionNames
                ? AIActionNames.normalize(value)
                : value.trim().toLowerCase(java.util.Locale.ROOT));
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static String normalizeRequired(String value, String field) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
