package ai.fabric.execution.chain;

import ai.fabric.execution.specialist.JsonSchemaOutputContract;
import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.execution.specialist.manifest.SpecialistConversationBinding;
import ai.fabric.execution.specialist.manifest.SpecialistInteractionCapability;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Startup-validating registry for bounded multi-specialist chains. */
public final class DefaultSpecialistChainRegistry
    implements SpecialistChainRegistry {

    private final Map<SpecialistChainId, RegisteredSpecialistChain> chains;

    public DefaultSpecialistChainRegistry(
        List<SpecialistChainDefinition<?>> definitions,
        SpecialistRegistry specialistRegistry,
        SpecialistClientFactory clientFactory,
        CanonicalJsonSupport canonicalJson,
        Duration maximumDuration,
        int maximumManagerDecisions,
        int maximumWorkerInvocations,
        int maximumParallelWorkers,
        int maximumInvocationsPerTarget,
        int maximumProjectedResultCharacters
    ) {
        this(
            new SpecialistChainRegistrationBundle(
                definitions == null
                    ? List.of()
                    : definitions.stream()
                        .map(SpecialistChainRegistration::javaDefinition)
                        .toList(),
                List.of(),
                0,
                0
            ),
            specialistRegistry,
            clientFactory,
            canonicalJson,
            maximumDuration,
            maximumManagerDecisions,
            maximumWorkerInvocations,
            maximumParallelWorkers,
            maximumInvocationsPerTarget,
            maximumProjectedResultCharacters
        );
    }

    public DefaultSpecialistChainRegistry(
        SpecialistChainRegistrationBundle registrations,
        SpecialistRegistry specialistRegistry,
        SpecialistClientFactory clientFactory,
        CanonicalJsonSupport canonicalJson,
        Duration maximumDuration,
        int maximumManagerDecisions,
        int maximumWorkerInvocations,
        int maximumParallelWorkers,
        int maximumInvocationsPerTarget,
        int maximumProjectedResultCharacters
    ) {
        Objects.requireNonNull(registrations, "registrations are required");
        Objects.requireNonNull(
            specialistRegistry,
            "specialistRegistry is required"
        );
        Objects.requireNonNull(clientFactory, "clientFactory is required");
        Objects.requireNonNull(canonicalJson, "canonicalJson is required");
        requirePositive(maximumDuration, "maximumDuration");
        requirePositive(maximumManagerDecisions, "maximumManagerDecisions");
        requirePositive(maximumWorkerInvocations, "maximumWorkerInvocations");
        requirePositive(maximumParallelWorkers, "maximumParallelWorkers");
        requirePositive(
            maximumInvocationsPerTarget,
            "maximumInvocationsPerTarget"
        );
        requirePositive(
            maximumProjectedResultCharacters,
            "maximumProjectedResultCharacters"
        );

        Map<SpecialistChainId, RegisteredSpecialistChain> validated =
            new LinkedHashMap<>();
        for (SpecialistChainRegistration registration
            : registrations.registrations()) {
                SpecialistChainRegistration required = Objects.requireNonNull(
                    registration,
                    "chain registration is required"
                );
                RegisteredSpecialistChain registered = validate(
                    required,
                    specialistRegistry,
                    clientFactory,
                    canonicalJson,
                    maximumDuration,
                    maximumManagerDecisions,
                    maximumWorkerInvocations,
                    maximumParallelWorkers,
                    maximumInvocationsPerTarget,
                    maximumProjectedResultCharacters
                );
                if (validated.putIfAbsent(
                        registered.id(),
                        registered
                    ) != null) {
                    throw invalid(
                        required.definition(),
                        "duplicates an existing chain ID"
                    );
                }
        }
        this.chains = Map.copyOf(validated);
    }

    @Override
    public Optional<RegisteredSpecialistChain> find(SpecialistChainId id) {
        return Optional.ofNullable(chains.get(id));
    }

    @Override
    public List<RegisteredSpecialistChain> list() {
        return chains.values().stream()
            .sorted(Comparator.comparing(value -> value.id().toString()))
            .toList();
    }

    private RegisteredSpecialistChain validate(
        SpecialistChainRegistration registration,
        SpecialistRegistry specialistRegistry,
        SpecialistClientFactory clientFactory,
        CanonicalJsonSupport canonicalJson,
        Duration maximumDuration,
        int maximumManagerDecisions,
        int maximumWorkerInvocations,
        int maximumParallelWorkers,
        int maximumInvocationsPerTarget,
        int maximumProjectedResultCharacters
    ) {
        SpecialistChainDefinition<?> definition = registration.definition();
        validateLimits(
            definition,
            maximumDuration,
            maximumManagerDecisions,
            maximumWorkerInvocations,
            maximumParallelWorkers,
            maximumInvocationsPerTarget,
            maximumProjectedResultCharacters
        );
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
                SpecialistChainManagerInput.class,
                SpecialistChainDirective.class
            );
        } catch (RuntimeException ex) {
            throw invalid(
                definition,
                "manager has an incompatible typed binding: "
                    + ex.getMessage()
            );
        }
        validateDirectiveSchema(definition, manager.definition());

        List<Map<String, Object>> fingerprintTargets = new ArrayList<>();
        definition.targets().forEach(target ->
            fingerprintTargets.add(validateTarget(
                definition,
                target,
                manager.definition(),
                specialistRegistry,
                clientFactory
            ))
        );

        LinkedHashMap<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("id", definition.id().toString());
        registration.identity().declarativeSemanticsHash().ifPresent(hash ->
            fingerprint.put("declarativeSemanticsHash", hash)
        );
        if (!registration.identity().schemaDependencies().isEmpty()) {
            fingerprint.put(
                "schemaDependencies",
                registration.identity().schemaDependencies().entrySet()
                    .stream()
                    .sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(Object::toString)
                    ))
                    .collect(java.util.stream.Collectors.toMap(
                        entry -> entry.getKey().toString(),
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                    ))
            );
        }
        fingerprint.put("managerSpecialist", manager.id().toString());
        fingerprint.put("managerContentHash", manager.contentHash());
        fingerprint.put("inputType", definition.inputType().getName());
        fingerprint.put("inputAdapterId", definition.inputAdapter().id());
        fingerprint.put(
            "inputAdapterClass",
            definition.inputAdapter().getClass().getName()
        );
        fingerprint.put("limits", definition.limits());
        fingerprint.put(
            "conversationPolicy",
            definition.conversationPolicy().name()
        );
        fingerprint.put("targets", fingerprintTargets);
        return new RegisteredSpecialistChain(
            definition,
            canonicalJson.hashValue(fingerprint),
            manager.contentHash(),
            registration.source(),
            registration.identity().resourceHash(),
            registration.identity().declarativeSemanticsHash(),
            registration.identity().schemaDependencies(),
            registration.identity().safeSource()
        );
    }

    private void validateLimits(
        SpecialistChainDefinition<?> definition,
        Duration maximumDuration,
        int maximumManagerDecisions,
        int maximumWorkerInvocations,
        int maximumParallelWorkers,
        int maximumInvocationsPerTarget,
        int maximumProjectedResultCharacters
    ) {
        SpecialistChainLimits limits = definition.limits();
        if (limits.maxDuration().compareTo(maximumDuration) > 0
            || limits.maxManagerDecisions() > maximumManagerDecisions
            || limits.maxWorkerInvocations() > maximumWorkerInvocations
            || limits.maxParallelWorkers() > maximumParallelWorkers
            || limits.maxInvocationsPerTarget()
                > maximumInvocationsPerTarget
            || limits.maxProjectedResultCharacters()
                > maximumProjectedResultCharacters) {
            throw invalid(
                definition,
                "limits exceed a deployment ceiling"
            );
        }
        long parallelTargets = definition.targets().stream()
            .filter(SpecialistChainTarget::parallelEligible)
            .count();
        if (limits.maxParallelWorkers() > parallelTargets
            && parallelTargets > 0) {
            throw invalid(
                definition,
                "maxParallelWorkers exceeds the parallel-eligible target count"
            );
        }
        if (limits.maxInvocationsPerTarget() != 1) {
            throw invalid(
                definition,
                "the first chain runtime requires maxInvocationsPerTarget=1"
            );
        }
    }

    private void validateManager(
        SpecialistChainDefinition<?> definition,
        SpecialistDefinition<?, ?> manager
    ) {
        var input = manager.inputAdapter();
        SpecialistChainConversationPolicy policy =
            definition.conversationPolicy();
        if (policy == SpecialistChainConversationPolicy.DISABLED) {
            if (input.interactionCapability()
                    != SpecialistInteractionCapability.NON_INTERACTIVE
                || input.conversationBinding()
                    != SpecialistConversationBinding.DISABLED) {
                throw invalid(
                    definition,
                    "non-conversational managers must be isolated from dialogue"
                );
            }
        } else {
            if (input.interactionCapability()
                != SpecialistInteractionCapability.DIALOGUE_CAPABLE) {
                throw invalid(
                    definition,
                    "conversational managers must be DIALOGUE_CAPABLE"
                );
            }
            if (policy == SpecialistChainConversationPolicy.REQUIRED
                && input.conversationBinding()
                    == SpecialistConversationBinding.DISABLED) {
                throw invalid(
                    definition,
                    "required conversation managers must accept a binding"
                );
            }
            if (policy == SpecialistChainConversationPolicy.OPTIONAL
                && input.conversationBinding()
                    != SpecialistConversationBinding.OPTIONAL) {
                throw invalid(
                    definition,
                    "optional conversation managers require OPTIONAL binding"
                );
            }
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
        if (manager.executionProfile().writeEnabled()
            || capabilities.retrievalEnabled()
            || !capabilities.requestedVectorSpaces().isEmpty()
            || !capabilities.visibleActions().isEmpty()
            || !capabilities.requestableReadActions().isEmpty()
            || !capabilities.proposableWriteActions().isEmpty()) {
            throw invalid(
                definition,
                "manager specialist cannot declare retrieval, actions, or writes"
            );
        }
    }

    private Map<String, Object> validateTarget(
        SpecialistChainDefinition<?> definition,
        SpecialistChainTarget<?, ?, ?> target,
        SpecialistDefinition<?, ?> manager,
        SpecialistRegistry specialistRegistry,
        SpecialistClientFactory clientFactory
    ) {
        if (target.delegationAllowed()
            && !manager.delegationPolicy().allows(target.specialistId())) {
            throw invalid(
                definition,
                "target " + target.specialistId()
                    + " is absent from the manager delegation allowlist"
            );
        }
        if (target.handoffAllowed()
            && !manager.handoffPolicy().allows(target.specialistId())) {
            throw invalid(
                definition,
                "target " + target.specialistId()
                    + " is absent from the manager handoff allowlist"
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
            target.inputMapper().chainRequestType()
        );
        requireSameType(
            definition,
            "target result projector request",
            definition.inputType(),
            target.resultProjector().chainRequestType()
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
        value.put("delegationAllowed", target.delegationAllowed());
        value.put("parallelEligible", target.parallelEligible());
        value.put("handoffAllowed", target.handoffAllowed());
        value.put("inputMapperId", target.inputMapper().id());
        value.put(
            "inputMapperClass",
            target.inputMapper().getClass().getName()
        );
        value.put(
            "inputType",
            target.inputMapper().targetInputType().getName()
        );
        value.put("resultProjectorId", target.resultProjector().id());
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
        SpecialistChainDefinition<?> definition,
        SpecialistDefinition<?, ?> worker
    ) {
        if (worker.executionProfile().writeEnabled()) {
            throw invalid(definition, "chain targets must be read-only");
        }
        var input = worker.inputAdapter();
        if (input.interactionCapability()
                != SpecialistInteractionCapability.NON_INTERACTIVE
            || input.conversationBinding()
                != SpecialistConversationBinding.DISABLED
            || input.recordValidatedTurns()) {
            throw invalid(
                definition,
                "chain targets must be non-interactive and conversation-isolated"
            );
        }
        if (input.inputContinuation().isPresent()) {
            throw invalid(
                definition,
                "chain targets cannot request user input"
            );
        }
    }

    private void validateDirectiveSchema(
        SpecialistChainDefinition<?> definition,
        SpecialistDefinition<?, ?> manager
    ) {
        if (!(manager.outputAdapter().outputContract()
            instanceof JsonSchemaOutputContract contract)) {
            throw invalid(
                definition,
                "manager must expose an exact JSON Schema directive contract"
            );
        }
        JsonNode schema = contract.schema();
        if (!schema.path("additionalProperties").isBoolean()
            || schema.path("additionalProperties").booleanValue()) {
            throw invalid(
                definition,
                "manager directive schema must reject unknown fields"
            );
        }
        Set<String> expectedDirectiveFields = Set.of(
            "type",
            "targets",
            "message",
            "reason",
            "supportingResultIds"
        );
        if (!fieldNames(schema.path("properties"))
                .equals(expectedDirectiveFields)
            || !textValues(schema.path("required"))
                .equals(expectedDirectiveFields)) {
            throw invalid(
                definition,
                "manager directive schema must require exactly the chain directive fields"
            );
        }
        Set<String> expectedTypes = new LinkedHashSet<>();
        for (SpecialistChainDirectiveType type
            : SpecialistChainDirectiveType.values()) {
            expectedTypes.add(type.name());
        }
        Set<String> declaredTypes = textEnum(
            schema.path("properties").path("type").path("enum")
        );
        if (!declaredTypes.equals(expectedTypes)) {
            throw invalid(
                definition,
                "manager directive type enum must exactly match the chain contract"
            );
        }
        JsonNode targetItems = schema.path("properties")
            .path("targets")
            .path("items");
        if (!targetItems.path("additionalProperties").isBoolean()
            || targetItems.path("additionalProperties").booleanValue()) {
            throw invalid(
                definition,
                "manager target-request schema must reject unknown fields"
            );
        }
        Set<String> expectedTargetFields = Set.of(
            "targetSpecialist",
            "objective"
        );
        if (!fieldNames(targetItems.path("properties"))
                .equals(expectedTargetFields)
            || !textValues(targetItems.path("required"))
                .equals(expectedTargetFields)) {
            throw invalid(
                definition,
                "manager target-request schema must require exactly the chain target fields"
            );
        }
        Set<String> expectedTargets = definition.targets().stream()
            .map(target -> target.specialistId().toString())
            .collect(java.util.stream.Collectors.toCollection(
                LinkedHashSet::new
            ));
        Set<String> declaredTargets = textEnum(
            targetItems.path("properties")
                .path("targetSpecialist")
                .path("enum")
        );
        if (!declaredTargets.equals(expectedTargets)) {
            throw invalid(
                definition,
                "manager target enum must exactly match registered chain targets"
            );
        }
        JsonNode supportingResults = schema.path("properties")
            .path("supportingResultIds");
        if (!"array".equals(supportingResults.path("type").asText())
            || !supportingResults.path("uniqueItems").asBoolean(false)
            || supportingResults.path("maxItems").asInt(-1)
                > SpecialistChainDirective.MAX_TARGETS
            || supportingResults.path("maxItems").asInt(-1) < 0
            || !"string".equals(
                supportingResults.path("items").path("type").asText()
            )) {
            throw invalid(
                definition,
                "manager supporting-result schema must be a bounded unique string array"
            );
        }
    }

    private Set<String> fieldNames(JsonNode object) {
        if (!object.isObject()) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        object.fieldNames().forEachRemaining(result::add);
        return Set.copyOf(result);
    }

    private Set<String> textValues(JsonNode values) {
        if (!values.isArray()) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || !result.add(value.textValue())) {
                return Set.of();
            }
        }
        return Set.copyOf(result);
    }

    private Set<String> textEnum(JsonNode values) {
        if (!values.isArray()) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || !result.add(value.textValue())) {
                return Set.of();
            }
        }
        return Set.copyOf(result);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void bindTarget(
        SpecialistClientFactory clientFactory,
        SpecialistChainTarget<?, ?, ?> target
    ) {
        clientFactory.bind(
            target.specialistId(),
            (Class) target.inputMapper().targetInputType(),
            (Class) target.resultProjector().targetOutputType()
        );
    }

    private RegisteredSpecialist requireSpecialist(
        SpecialistChainDefinition<?> definition,
        SpecialistRegistry registry,
        ai.fabric.execution.specialist.SpecialistId specialistId,
        String role
    ) {
        return registry.findRegistered(specialistId).orElseThrow(() ->
            invalid(
                definition,
                role + " references unknown specialist " + specialistId
            )
        );
    }

    private void requireSameType(
        SpecialistChainDefinition<?> definition,
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
        SpecialistChainDefinition<?> definition,
        String message
    ) {
        return new IllegalArgumentException(
            "Specialist chain " + definition.id() + " " + message
        );
    }

    private static Duration requirePositive(
        Duration value,
        String field
    ) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static int requirePositive(int value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
