package ai.fabric.execution.specialist;

import ai.fabric.intent.action.AIActionRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable startup-validated specialist registry.
 */
public final class DefaultSpecialistRegistry implements SpecialistRegistry {

    private final Map<SpecialistId, RegisteredSpecialist> definitions;
    private final String contentHash;

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
        this(
            javaDefinitions(definitions),
            new SpecialistDefinitionValidator(
                actionRegistry,
                knownModes,
                registeredVectorSpaces
            )
        );
    }

    public DefaultSpecialistRegistry(
        List<RegisteredSpecialist> definitions,
        SpecialistDefinitionValidator validator
    ) {
        Objects.requireNonNull(validator, "validator is required");
        Map<SpecialistId, RegisteredSpecialist> validated =
            new LinkedHashMap<>();
        for (RegisteredSpecialist registered :
            definitions == null
                ? List.<RegisteredSpecialist>of()
                : definitions) {
            Objects.requireNonNull(
                registered,
                "registered specialist must not be null"
            );
            validator.validate(registered.definition());
            if (validated.putIfAbsent(registered.id(), registered) != null) {
                throw new IllegalStateException(
                    "Duplicate specialist definition: " + registered.id()
                );
            }
        }
        validateDelegationTargets(validated);
        validateHandoffTargets(validated);
        this.definitions = java.util.Collections.unmodifiableMap(
            new LinkedHashMap<>(validated)
        );
        this.contentHash = SpecialistRegistry.super.registryContentHash();
    }

    private void validateHandoffTargets(
        Map<SpecialistId, RegisteredSpecialist> definitions
    ) {
        definitions.values().forEach(source ->
            source.definition().handoffPolicy().allowedTargets().forEach(
                targetId -> {
                    if (source.id().equals(targetId)) {
                        throw new IllegalStateException(
                            "Specialist " + source.id()
                                + " cannot hand off to itself"
                        );
                    }
                    RegisteredSpecialist target = definitions.get(targetId);
                    if (target == null) {
                        throw new IllegalStateException(
                            "Specialist " + source.id()
                                + " references unregistered handoff target "
                                + targetId
                        );
                    }
                    if (!target.definition().executionProfile()
                        .requestedCapabilities()
                        .proposableWriteActions()
                        .isEmpty()) {
                        throw new IllegalStateException(
                            "Specialist " + source.id()
                                + " cannot hand off to WRITE-capable target "
                                + targetId
                        );
                    }
                }
            )
        );
    }

    private void validateDelegationTargets(
        Map<SpecialistId, RegisteredSpecialist> definitions
    ) {
        definitions.values().forEach(source ->
            source.definition().delegationPolicy().allowedTargets().forEach(
                targetId -> {
                    if (source.id().equals(targetId)) {
                        throw new IllegalStateException(
                            "Specialist " + source.id()
                                + " cannot delegate to itself"
                        );
                    }
                    RegisteredSpecialist target = definitions.get(targetId);
                    if (target == null) {
                        throw new IllegalStateException(
                            "Specialist " + source.id()
                                + " references unregistered delegation target "
                                + targetId
                        );
                    }
                    if (!target.definition().executionProfile()
                        .requestedCapabilities()
                        .proposableWriteActions()
                        .isEmpty()) {
                        throw new IllegalStateException(
                            "Specialist " + source.id()
                                + " cannot delegate to WRITE-capable target "
                                + targetId
                        );
                    }
                }
            )
        );
    }

    @Override
    public java.util.Optional<SpecialistDefinition<?, ?>> find(SpecialistId id) {
        return findRegistered(id).map(RegisteredSpecialist::definition);
    }

    @Override
    public List<SpecialistDefinition<?, ?>> list() {
        return definitions.values().stream()
            .map(RegisteredSpecialist::definition)
            .toList();
    }

    @Override
    public java.util.Optional<RegisteredSpecialist> findRegistered(
        SpecialistId id
    ) {
        return java.util.Optional.ofNullable(definitions.get(id));
    }

    @Override
    public List<RegisteredSpecialist> listRegistered() {
        return List.copyOf(definitions.values());
    }

    @Override
    public String registryContentHash() {
        return contentHash;
    }

    private static List<RegisteredSpecialist> javaDefinitions(
        List<SpecialistDefinition<?, ?>> definitions
    ) {
        return (definitions == null
                ? List.<SpecialistDefinition<?, ?>>of()
                : definitions)
            .stream()
            .map(RegisteredSpecialist::javaDefinition)
            .toList();
    }
}
