package ai.fabric.intent.orchestration.capability;

import ai.fabric.intent.action.AIActionNames;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Capabilities requested by a trusted runtime or specialist definition.
 *
 * <p>This declaration never grants authority. It is input to effective capability resolution.</p>
 */
public record RequestedCapabilityProfile(
    boolean retrievalEnabled,
    Set<String> requestedVectorSpaces,
    Set<String> visibleActions,
    Set<String> requestableReadActions,
    Set<String> proposableWriteActions
) {
    public RequestedCapabilityProfile {
        requestedVectorSpaces = normalizeNames(requestedVectorSpaces, false);
        visibleActions = normalizeNames(visibleActions, true);
        requestableReadActions = normalizeNames(requestableReadActions, true);
        proposableWriteActions = normalizeNames(proposableWriteActions, true);
        if (!visibleActions.containsAll(requestableReadActions)
            || !visibleActions.containsAll(proposableWriteActions)) {
            throw new IllegalArgumentException(
                "Read and write action requests must be included in visibleActions"
            );
        }
    }

    public static RequestedCapabilityProfile retrievalOnly(Set<String> vectorSpaces) {
        return new RequestedCapabilityProfile(true, vectorSpaces, Set.of(), Set.of(), Set.of());
    }

    private static Set<String> normalizeNames(Set<String> values, boolean actionNames) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String candidate = actionNames
                ? AIActionNames.normalize(value)
                : value.trim().toLowerCase(java.util.Locale.ROOT);
            if (!candidate.isBlank()) {
                normalized.add(candidate);
            }
        }
        return Collections.unmodifiableSet(normalized);
    }
}
