package ai.fabric.execution.manager;

import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.execution.specialist.manifest.SpecialistConversationBinding;
import ai.fabric.execution.specialist.manifest.SpecialistInteractionCapability;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Startup-validating registry for bounded supervised conversation managers.
 */
public final class DefaultConversationManagerRegistry
    implements ConversationManagerRegistry {

    private final Map<
        ConversationManagerId,
        RegisteredConversationManager
    > managers;

    public DefaultConversationManagerRegistry(
        List<ConversationManagerDefinition<?>> definitions,
        SpecialistRegistry specialistRegistry,
        SpecialistClientFactory clientFactory,
        CanonicalJsonSupport canonicalJson,
        Duration maximumDuration
    ) {
        Objects.requireNonNull(
            specialistRegistry,
            "specialistRegistry is required"
        );
        Objects.requireNonNull(clientFactory, "clientFactory is required");
        Objects.requireNonNull(canonicalJson, "canonicalJson is required");
        requirePositive(maximumDuration, "maximumDuration");

        Map<
            ConversationManagerId,
            RegisteredConversationManager
        > validated = new LinkedHashMap<>();
        if (definitions != null) {
            for (ConversationManagerDefinition<?> definition : definitions) {
                RegisteredConversationManager registered = validate(
                    Objects.requireNonNull(
                        definition,
                        "manager definition is required"
                    ),
                    specialistRegistry,
                    clientFactory,
                    canonicalJson,
                    maximumDuration
                );
                if (validated.putIfAbsent(
                        registered.id(),
                        registered
                    ) != null) {
                    throw invalid(
                        definition,
                        "duplicates an existing manager ID"
                    );
                }
            }
        }
        this.managers = Map.copyOf(validated);
    }

    @Override
    public Optional<RegisteredConversationManager> find(
        ConversationManagerId id
    ) {
        return Optional.ofNullable(managers.get(id));
    }

    @Override
    public List<RegisteredConversationManager> list() {
        return managers.values().stream()
            .sorted(Comparator.comparing(value -> value.id().toString()))
            .toList();
    }

    private RegisteredConversationManager validate(
        ConversationManagerDefinition<?> definition,
        SpecialistRegistry specialistRegistry,
        SpecialistClientFactory clientFactory,
        CanonicalJsonSupport canonicalJson,
        Duration maximumDuration
    ) {
        if (definition.maximumDuration().compareTo(maximumDuration) > 0) {
            throw invalid(
                definition,
                "maximumDuration exceeds the deployment ceiling"
            );
        }
        requireSameType(
            definition,
            "input adapter",
            definition.inputType(),
            definition.inputAdapter().inputType()
        );

        RegisteredSpecialist manager = requireSpecialist(
            definition,
            specialistRegistry,
            definition.managerSpecialistId(),
            "manager"
        );
        validateManager(definition, manager.definition());
        try {
            clientFactory.bind(
                manager.id(),
                ConversationManagerInput.class,
                ConversationManagerDirective.class
            );
        } catch (RuntimeException ex) {
            throw invalid(
                definition,
                "manager has an incompatible typed binding: "
                    + ex.getMessage()
            );
        }

        List<Map<String, Object>> fingerprintTargets =
            new ArrayList<>();
        definition.targets().stream()
            .sorted(Comparator.comparing(value ->
                value.specialistId().toString()
            ))
            .forEach(target -> fingerprintTargets.add(validateTarget(
                definition,
                target,
                manager.definition(),
                specialistRegistry,
                clientFactory
            )));

        LinkedHashMap<String, Object> fingerprint =
            new LinkedHashMap<>();
        fingerprint.put("id", definition.id().toString());
        fingerprint.put(
            "managerSpecialist",
            manager.id().toString()
        );
        fingerprint.put(
            "managerContentHash",
            manager.contentHash()
        );
        fingerprint.put(
            "inputType",
            definition.inputType().getName()
        );
        fingerprint.put(
            "inputAdapterId",
            definition.inputAdapter().id().toString()
        );
        fingerprint.put(
            "inputAdapterClass",
            definition.inputAdapter().getClass().getName()
        );
        fingerprint.put(
            "maximumDuration",
            definition.maximumDuration().toString()
        );
        fingerprint.put("targets", fingerprintTargets);
        return new RegisteredConversationManager(
            definition,
            canonicalJson.hashValue(fingerprint)
        );
    }

    private void validateManager(
        ConversationManagerDefinition<?> definition,
        SpecialistDefinition<?, ?> manager
    ) {
        var input = manager.inputAdapter();
        if (input.interactionCapability()
            != SpecialistInteractionCapability.DIALOGUE_CAPABLE) {
            throw invalid(
                definition,
                "manager specialist must be DIALOGUE_CAPABLE"
            );
        }
        if (input.conversationBinding()
            == SpecialistConversationBinding.DISABLED) {
            throw invalid(
                definition,
                "manager specialist must accept a conversation binding"
            );
        }
        if (input.recordValidatedTurns()) {
            throw invalid(
                definition,
                "manager specialist must defer conversation recording"
            );
        }
        if (input.inputContinuation().isPresent()) {
            throw invalid(
                definition,
                "manager specialist cannot declare input continuation"
            );
        }
        var capabilities = manager.executionProfile()
            .requestedCapabilities();
        if (capabilities.retrievalEnabled()
            || !capabilities.requestedVectorSpaces().isEmpty()
            || !capabilities.visibleActions().isEmpty()
            || !capabilities.requestableReadActions().isEmpty()
            || !capabilities.proposableWriteActions().isEmpty()) {
            throw invalid(
                definition,
                "manager specialist cannot declare retrieval or actions"
            );
        }
    }

    private Map<String, Object> validateTarget(
        ConversationManagerDefinition<?> definition,
        ConversationManagerTarget<?, ?, ?> target,
        SpecialistDefinition<?, ?> manager,
        SpecialistRegistry specialistRegistry,
        SpecialistClientFactory clientFactory
    ) {
        if (!manager.delegationPolicy().allows(target.specialistId())) {
            throw invalid(
                definition,
                "target " + target.specialistId()
                    + " is absent from the manager delegation allowlist"
            );
        }
        RegisteredSpecialist worker = requireSpecialist(
            definition,
            specialistRegistry,
            target.specialistId(),
            "target"
        );
        validateWorker(definition, worker.definition());
        requireSameType(
            definition,
            "target input mapper request",
            definition.inputType(),
            target.inputMapper().managerRequestType()
        );
        requireSameType(
            definition,
            "target result projector request",
            definition.inputType(),
            target.resultProjector().managerRequestType()
        );
        try {
            bindTarget(clientFactory, target);
        } catch (RuntimeException ex) {
            throw invalid(
                definition,
                "target " + target.specialistId()
                    + " has an incompatible typed binding: "
                    + ex.getMessage()
            );
        }

        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("specialist", worker.id().toString());
        value.put("contentHash", worker.contentHash());
        value.put("description", target.description());
        value.put(
            "inputMapperId",
            target.inputMapper().id().toString()
        );
        value.put(
            "inputMapperClass",
            target.inputMapper().getClass().getName()
        );
        value.put(
            "inputType",
            target.inputMapper().targetInputType().getName()
        );
        value.put(
            "resultProjectorId",
            target.resultProjector().id().toString()
        );
        value.put(
            "resultProjectorClass",
            target.resultProjector().getClass().getName()
        );
        value.put(
            "outputType",
            target.resultProjector().targetOutputType().getName()
        );
        return Map.copyOf(value);
    }

    private void validateWorker(
        ConversationManagerDefinition<?> definition,
        SpecialistDefinition<?, ?> worker
    ) {
        if (worker.executionProfile().writeEnabled()) {
            throw invalid(
                definition,
                "manager targets must be read-only"
            );
        }
        var input = worker.inputAdapter();
        if (input.interactionCapability()
            != SpecialistInteractionCapability.NON_INTERACTIVE) {
            throw invalid(
                definition,
                "manager targets must be non-interactive"
            );
        }
        if (input.conversationBinding()
                != SpecialistConversationBinding.DISABLED
            || input.recordValidatedTurns()) {
            throw invalid(
                definition,
                "manager targets must be conversation-isolated"
            );
        }
        if (input.inputContinuation().isPresent()) {
            throw invalid(
                definition,
                "manager targets cannot request input in this release"
            );
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void bindTarget(
        SpecialistClientFactory clientFactory,
        ConversationManagerTarget<?, ?, ?> target
    ) {
        clientFactory.bind(
            target.specialistId(),
            (Class) target.inputMapper().targetInputType(),
            (Class) target.resultProjector().targetOutputType()
        );
    }

    private RegisteredSpecialist requireSpecialist(
        ConversationManagerDefinition<?> definition,
        SpecialistRegistry specialistRegistry,
        ai.fabric.execution.specialist.SpecialistId specialistId,
        String role
    ) {
        return specialistRegistry.findRegistered(specialistId)
            .orElseThrow(() -> invalid(
                definition,
                role + " references unknown specialist " + specialistId
            ));
    }

    private void requireSameType(
        ConversationManagerDefinition<?> definition,
        String role,
        Class<?> expected,
        Class<?> actual
    ) {
        if (!expected.equals(actual)) {
            throw invalid(
                definition,
                role + " must use " + expected.getName()
                    + " but uses " + actual.getName()
            );
        }
    }

    private IllegalArgumentException invalid(
        ConversationManagerDefinition<?> definition,
        String message
    ) {
        return new IllegalArgumentException(
            "Conversation manager " + definition.id() + " " + message
        );
    }

    private static Duration requirePositive(
        Duration value,
        String field
    ) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                field + " must be positive"
            );
        }
        return value;
    }
}
