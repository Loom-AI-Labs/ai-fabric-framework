package ai.fabric.execution.specialist;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionNames;
import ai.fabric.intent.action.AIActionRegistry;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable startup-validated specialist registry.
 */
public final class DefaultSpecialistRegistry implements SpecialistRegistry {

    private final Map<SpecialistId, SpecialistDefinition<?, ?>> definitions;

    public DefaultSpecialistRegistry(
        List<SpecialistDefinition<?, ?>> definitions,
        AIActionRegistry actionRegistry,
        Set<String> knownModes
    ) {
        this(definitions, actionRegistry, knownModes, Set.of());
    }

    public DefaultSpecialistRegistry(
        List<SpecialistDefinition<?, ?>> definitions,
        AIActionRegistry actionRegistry,
        Set<String> knownModes,
        Set<String> registeredVectorSpaces
    ) {
        Objects.requireNonNull(actionRegistry, "actionRegistry is required");
        Set<String> normalizedModes = normalize(knownModes);
        Set<String> normalizedVectorSpaces = normalize(registeredVectorSpaces);
        Map<String, AIActionMetaData> registeredActions = new LinkedHashMap<>();
        List<AIActionMetaData> metadata = actionRegistry.getAllMetadata();
        if (metadata != null) {
            metadata.stream()
                .filter(Objects::nonNull)
                .filter(action -> action.getName() != null)
                .forEach(action ->
                    registeredActions.put(
                        AIActionNames.normalize(action.getName()),
                        action
                    )
                );
        }

        Map<SpecialistId, SpecialistDefinition<?, ?>> validated = new LinkedHashMap<>();
        for (SpecialistDefinition<?, ?> definition :
            definitions != null ? definitions : List.<SpecialistDefinition<?, ?>>of()) {
            Objects.requireNonNull(definition, "specialist definition must not be null");
            if (validated.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalStateException(
                    "Duplicate specialist definition: " + definition.id()
                );
            }
            String mode = definition.executionProfile()
                .mode()
                .toLowerCase(Locale.ROOT);
            if (!normalizedModes.contains(mode)) {
                throw new IllegalStateException(
                    "Specialist " + definition.id() + " references unknown Mode " + mode
                );
            }
            Set<String> requested = new LinkedHashSet<>(
                definition.executionProfile().requestedCapabilities().visibleActions()
            );
            requested.addAll(
                definition.executionProfile().requestedCapabilities().requestableReadActions()
            );
            requested.addAll(
                definition.executionProfile().requestedCapabilities().proposableWriteActions()
            );
            Set<String> missing = new LinkedHashSet<>(requested);
            missing.removeAll(registeredActions.keySet());
            if (!missing.isEmpty()) {
                throw new IllegalStateException(
                    "Specialist " + definition.id()
                        + " references unregistered actions " + missing
                );
            }
            validateActionModes(definition, registeredActions);
            if (definition.executionProfile()
                    .requestedCapabilities()
                    .retrievalEnabled()
                && definition.executionProfile()
                    .requestedCapabilities()
                    .requestedVectorSpaces()
                    .isEmpty()) {
                throw new IllegalStateException(
                    "Specialist " + definition.id()
                        + " enables retrieval without a bounded vector-space scope"
                );
            }
            if (!normalizedVectorSpaces.isEmpty()) {
                Set<String> missingSpaces = new LinkedHashSet<>(
                    definition.executionProfile()
                        .requestedCapabilities()
                        .requestedVectorSpaces()
                );
                missingSpaces.removeAll(normalizedVectorSpaces);
                if (!missingSpaces.isEmpty()) {
                    throw new IllegalStateException(
                        "Specialist " + definition.id()
                            + " references unregistered vector spaces "
                            + missingSpaces
                    );
                }
            }
        }
        this.definitions = Collections.unmodifiableMap(validated);
    }

    @Override
    public java.util.Optional<SpecialistDefinition<?, ?>> find(SpecialistId id) {
        return java.util.Optional.ofNullable(definitions.get(id));
    }

    @Override
    public List<SpecialistDefinition<?, ?>> list() {
        return List.copyOf(definitions.values());
    }

    private void validateActionModes(
        SpecialistDefinition<?, ?> definition,
        Map<String, AIActionMetaData> registeredActions
    ) {
        var capabilities =
            definition.executionProfile().requestedCapabilities();
        for (String action : capabilities.requestableReadActions()) {
            if (registeredActions.get(action).getAccessMode()
                != ai.fabric.intent.action.ActionAccessMode.READ) {
                throw new IllegalStateException(
                    "Specialist " + definition.id()
                        + " declares non-READ action " + action
                        + " as requestable READ"
                );
            }
        }
        for (String action : capabilities.proposableWriteActions()) {
            AIActionMetaData metadata = registeredActions.get(action);
            if (metadata.getAccessMode()
                == ai.fabric.intent.action.ActionAccessMode.READ) {
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
    }

    private Set<String> normalize(Set<String> values) {
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
