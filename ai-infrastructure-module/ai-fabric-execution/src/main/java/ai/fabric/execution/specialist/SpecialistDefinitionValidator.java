package ai.fabric.execution.specialist;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionNames;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shared startup validation for Java and manifest specialist definitions.
 */
public final class SpecialistDefinitionValidator {

    private final Map<String, AIActionMetaData> registeredActions;
    private final Set<String> knownModes;
    private final Set<String> registeredVectorSpaces;

    public SpecialistDefinitionValidator(
        AIActionRegistry actionRegistry,
        Set<String> knownModes,
        Set<String> registeredVectorSpaces
    ) {
        Objects.requireNonNull(actionRegistry, "actionRegistry is required");
        this.knownModes = normalize(knownModes);
        this.registeredVectorSpaces = normalize(registeredVectorSpaces);
        Map<String, AIActionMetaData> actions = new LinkedHashMap<>();
        List<AIActionMetaData> metadata = actionRegistry.getAllMetadata();
        if (metadata != null) {
            metadata.stream()
                .filter(Objects::nonNull)
                .filter(action -> action.getName() != null)
                .forEach(action ->
                    actions.put(
                        AIActionNames.normalize(action.getName()),
                        action
                    )
                );
        }
        this.registeredActions = Map.copyOf(actions);
    }

    public void validate(SpecialistDefinition<?, ?> definition) {
        Objects.requireNonNull(
            definition,
            "specialist definition must not be null"
        );
        String mode = definition.executionProfile()
            .mode()
            .toLowerCase(Locale.ROOT);
        if (!knownModes.contains(mode)) {
            throw new IllegalStateException(
                "Specialist " + definition.id()
                    + " references unknown Mode " + mode
            );
        }
        var capabilities =
            definition.executionProfile().requestedCapabilities();
        Set<String> requested = new LinkedHashSet<>(
            capabilities.visibleActions()
        );
        requested.addAll(capabilities.requestableReadActions());
        requested.addAll(capabilities.proposableWriteActions());
        Set<String> missing = new LinkedHashSet<>(requested);
        missing.removeAll(registeredActions.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                "Specialist " + definition.id()
                    + " references unregistered actions " + missing
            );
        }
        if (!capabilities.visibleActions()
            .containsAll(capabilities.requestableReadActions())
            || !capabilities.visibleActions()
                .containsAll(capabilities.proposableWriteActions())) {
            throw new IllegalStateException(
                "Specialist " + definition.id()
                    + " must include every requestable or proposable action in visible actions"
            );
        }
        validateActionModes(definition);
        if (capabilities.retrievalEnabled()
            && capabilities.requestedVectorSpaces().isEmpty()) {
            throw new IllegalStateException(
                "Specialist " + definition.id()
                    + " enables retrieval without a bounded vector-space scope"
            );
        }
        if (!registeredVectorSpaces.isEmpty()) {
            Set<String> missingSpaces = new LinkedHashSet<>(
                capabilities.requestedVectorSpaces()
            );
            missingSpaces.removeAll(registeredVectorSpaces);
            if (!missingSpaces.isEmpty()) {
                throw new IllegalStateException(
                    "Specialist " + definition.id()
                        + " references unregistered vector spaces "
                        + missingSpaces
                );
            }
        }
    }

    private void validateActionModes(SpecialistDefinition<?, ?> definition) {
        var capabilities =
            definition.executionProfile().requestedCapabilities();
        for (String action : capabilities.requestableReadActions()) {
            if (registeredActions.get(action).getAccessMode()
                != ActionAccessMode.READ) {
                throw new IllegalStateException(
                    "Specialist " + definition.id()
                        + " declares non-READ action " + action
                        + " as requestable READ"
                );
            }
        }
        for (String action : capabilities.proposableWriteActions()) {
            AIActionMetaData metadata = registeredActions.get(action);
            if (metadata.getAccessMode() == ActionAccessMode.READ) {
                throw new IllegalStateException(
                    "Specialist " + definition.id()
                        + " declares READ action " + action
                        + " as proposable WRITE"
                );
            }
            if (!metadata.isConfirmationRequired()) {
                throw new IllegalStateException(
                    "Specialist " + definition.id()
                        + " declares write action " + action
                        + " without application-owned confirmation"
                );
            }
        }
        if (!capabilities.proposableWriteActions().isEmpty()
            && definition.executionProfile().writePolicy()
                != SpecialistWritePolicy.CONFIRMATION_RECEIPT_REQUIRED) {
            throw new IllegalStateException(
                "Specialist " + definition.id()
                    + " requires CONFIRMATION_RECEIPT_REQUIRED for write proposals"
            );
        }
    }

    private static Set<String> normalize(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(value -> value.toLowerCase(Locale.ROOT))
            .forEach(normalized::add);
        return Set.copyOf(normalized);
    }
}
