package ai.fabric.execution.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.specialist.ExecutionStrategy;
import ai.fabric.execution.specialist.JsonSchemaOutputContract;
import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistDefinitionSource;
import ai.fabric.execution.specialist.SpecialistDelegationPolicy;
import ai.fabric.execution.specialist.SpecialistExecutionProfile;
import ai.fabric.execution.specialist.SpecialistHandoffPolicy;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.SpecialistIdentity;
import ai.fabric.execution.specialist.SpecialistInputAdapter;
import ai.fabric.execution.specialist.SpecialistInstructions;
import ai.fabric.execution.specialist.SpecialistLimits;
import ai.fabric.execution.specialist.SpecialistOutputAdapter;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.SpecialistWritePolicy;
import ai.fabric.execution.specialist.client.SpecialistClient;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.execution.specialist.manifest.SpecialistConversationBinding;
import ai.fabric.execution.specialist.manifest.SpecialistInteractionCapability;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaId;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.capability.RequestedCapabilityProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultSpecialistChainRegistryTest {

    private static final SpecialistId MANAGER =
        SpecialistId.of("incident-chain-manager", "1");
    private static final SpecialistId HEALTH =
        SpecialistId.of("service-health-reader", "1");
    private static final SpecialistId CHANGE =
        SpecialistId.of("change-risk-reader", "1");
    private static final List<SpecialistId> TARGETS =
        List.of(HEALTH, CHANGE);
    private static final ObjectMapper OBJECT_MAPPER =
        new ObjectMapper().findAndRegisterModules();

    @Test
    void validatesAndFingerprintsAClosedTypedChain() {
        SpecialistRegistry specialists = specialists(
            manager(TARGETS, Set.of(CHANGE), directiveSchema(TARGETS)),
            worker(HEALTH, false, false),
            worker(CHANGE, false, false)
        );
        SpecialistChainDefinition<String> definition = definition(
            "Inspect current health.",
            conservativeLimits()
        );

        RegisteredSpecialistChain first = registry(
            List.of(definition),
            specialists
        ).require(definition.id());
        RegisteredSpecialistChain second = registry(
            List.of(definition),
            specialists
        ).require(definition.id());
        RegisteredSpecialistChain changed = registry(
            List.of(definition(
                "Inspect health, saturation, and alerts.",
                conservativeLimits()
            )),
            specialists
        ).require(definition.id());

        assertThat(first.contentHash())
            .hasSize(64)
            .isEqualTo(second.contentHash())
            .isNotEqualTo(changed.contentHash());
        assertThat(first.managerContentHash()).hasSize(64);
        assertThat(first.definition().targets())
            .extracting(target -> target.specialistId().toString())
            .containsExactly(HEALTH.toString(), CHANGE.toString());
    }

    @Test
    void rejectsUnknownSpecialistsAndDuplicateChainIds() {
        SpecialistChainDefinition<String> definition = definition(
            "Inspect current health.",
            conservativeLimits()
        );

        assertThatThrownBy(() -> registry(
            List.of(definition),
            specialists()
        )).hasMessageContaining("manager references unknown specialist");

        SpecialistRegistry managerOnly = specialists(
            manager(TARGETS, Set.of(CHANGE), directiveSchema(TARGETS))
        );
        assertThatThrownBy(() -> registry(
            List.of(definition),
            managerOnly
        )).hasMessageContaining("target references unknown specialist");

        SpecialistRegistry all = validSpecialists();
        assertThatThrownBy(() -> registry(
            List.of(definition, definition),
            all
        )).hasMessageContaining("duplicates an existing chain ID");
    }

    @Test
    void rejectsManagersWithCapabilitiesOrConversationSideEffects() {
        SpecialistChainDefinition<String> definition = definition(
            "Inspect current health.",
            conservativeLimits()
        );
        SpecialistDefinition<SpecialistChainManagerInput,
            SpecialistChainDirective> recording = manager(
                TARGETS,
                Set.of(CHANGE),
                directiveSchema(TARGETS),
                SpecialistInteractionCapability.DIALOGUE_CAPABLE,
                SpecialistConversationBinding.REQUIRED,
                true,
                false
            );
        SpecialistDefinition<SpecialistChainManagerInput,
            SpecialistChainDirective> capabilityBearing = manager(
                TARGETS,
                Set.of(CHANGE),
                directiveSchema(TARGETS),
                SpecialistInteractionCapability.DIALOGUE_CAPABLE,
                SpecialistConversationBinding.REQUIRED,
                false,
                true
            );

        assertThatThrownBy(() -> registry(
            List.of(definition),
            specialists(recording, worker(HEALTH, false, false),
                worker(CHANGE, false, false))
        )).hasMessageContaining("must defer conversation recording");
        assertThatThrownBy(() -> registry(
            List.of(definition),
            specialists(capabilityBearing, worker(HEALTH, false, false),
                worker(CHANGE, false, false))
        )).hasMessageContaining(
            "manager specialist cannot declare retrieval, actions, or writes"
        );
    }

    @Test
    void rejectsManagersThatDoNotMatchConversationPolicy() {
        SpecialistDefinition<SpecialistChainManagerInput,
            SpecialistChainDirective> nonDialogue = manager(
                TARGETS,
                Set.of(CHANGE),
                directiveSchema(TARGETS),
                SpecialistInteractionCapability.NON_INTERACTIVE,
                SpecialistConversationBinding.DISABLED,
                false,
                false
            );
        SpecialistRegistry specialists = specialists(
            nonDialogue,
            worker(HEALTH, false, false),
            worker(CHANGE, false, false)
        );

        assertThatThrownBy(() -> registry(
            List.of(definition(
                "Inspect current health.",
                conservativeLimits()
            )),
            specialists
        )).hasMessageContaining(
            "conversational managers must be DIALOGUE_CAPABLE"
        );
    }

    @Test
    void rejectsTargetsOutsideManagerPolicies() {
        SpecialistDefinition<SpecialistChainManagerInput,
            SpecialistChainDirective> missingDelegation = manager(
                List.of(HEALTH),
                Set.of(CHANGE),
                directiveSchema(TARGETS)
            );
        SpecialistDefinition<SpecialistChainManagerInput,
            SpecialistChainDirective> missingHandoff = manager(
                TARGETS,
                Set.of(),
                directiveSchema(TARGETS)
            );

        assertThatThrownBy(() -> registry(
            List.of(definition(
                "Inspect current health.",
                conservativeLimits()
            )),
            specialists(missingDelegation,
                worker(HEALTH, false, false),
                worker(CHANGE, false, false))
        )).hasMessageContaining("delegation allowlist");
        assertThatThrownBy(() -> registry(
            List.of(definition(
                "Inspect current health.",
                conservativeLimits()
            )),
            specialists(missingHandoff,
                worker(HEALTH, false, false),
                worker(CHANGE, false, false))
        )).hasMessageContaining("handoff allowlist");
    }

    @Test
    void rejectsWriteOrInteractiveWorkers() {
        SpecialistChainDefinition<String> definition = definition(
            "Inspect current health.",
            conservativeLimits()
        );

        assertThatThrownBy(() -> registry(
            List.of(definition),
            specialists(
                manager(TARGETS, Set.of(CHANGE), directiveSchema(TARGETS)),
                worker(HEALTH, true, false),
                worker(CHANGE, false, false)
            )
        )).hasMessageContaining("chain targets must be read-only");
        assertThatThrownBy(() -> registry(
            List.of(definition),
            specialists(
                manager(TARGETS, Set.of(CHANGE), directiveSchema(TARGETS)),
                worker(HEALTH, false, true),
                worker(CHANGE, false, false)
            )
        )).hasMessageContaining(
            "chain targets must be non-interactive and conversation-isolated"
        );
    }

    @Test
    void rejectsInexactDirectiveSchemas() {
        SpecialistChainDefinition<String> definition = definition(
            "Inspect current health.",
            conservativeLimits()
        );
        JsonNode missingType = directiveSchema(TARGETS).deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) missingType
            .at("/properties/type/enum")).remove(0);
        JsonNode inventedTarget = directiveSchema(
            List.of(HEALTH, SpecialistId.of("invented-worker", "1"))
        );
        JsonNode openTarget = directiveSchema(TARGETS).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) openTarget
            .at("/properties/targets/items"))
            .put("additionalProperties", true);

        assertSchemaRejected(definition, missingType, "type enum");
        assertSchemaRejected(definition, inventedTarget, "target enum");
        assertSchemaRejected(
            definition,
            openTarget,
            "target-request schema must reject unknown fields"
        );
    }

    @Test
    void rejectsTypedBoundaryAndDeploymentLimitMismatches() {
        SpecialistRegistry specialists = validSpecialists();
        @SuppressWarnings({"rawtypes", "unchecked"})
        SpecialistChainTarget wrongMapper =
            new SpecialistChainTarget(
                HEALTH,
                "Inspect current health.",
                mapper(Integer.class, String.class, "wrong-mapper"),
                projector(String.class, Integer.class, "health-result"),
                true,
                true,
                false
            );
        @SuppressWarnings({"rawtypes", "unchecked"})
        SpecialistChainDefinition<String> wrong =
            new SpecialistChainDefinition(
                SpecialistChainId.of("wrong-chain", "1"),
                MANAGER,
                String.class,
                chainInput(),
                List.of(wrongMapper, changeTarget()),
                conservativeLimits(),
                SpecialistChainConversationPolicy.REQUIRED
            );

        assertThatThrownBy(() -> registry(List.of(wrong), specialists))
            .hasMessageContaining("target input mapper request");

        SpecialistChainDefinition<String> tooSlow = definition(
            "Inspect current health.",
            new SpecialistChainLimits(
                Duration.ofMinutes(3),
                4,
                2,
                2,
                1,
                8_000
            )
        );
        assertThatThrownBy(() -> registry(List.of(tooSlow), specialists))
            .hasMessageContaining("limits exceed a deployment ceiling");
    }

    private void assertSchemaRejected(
        SpecialistChainDefinition<String> definition,
        JsonNode schema,
        String message
    ) {
        assertThatThrownBy(() -> registry(
            List.of(definition),
            specialists(
                manager(TARGETS, Set.of(CHANGE), schema),
                worker(HEALTH, false, false),
                worker(CHANGE, false, false)
            )
        )).hasMessageContaining(message);
    }

    private DefaultSpecialistChainRegistry registry(
        List<SpecialistChainDefinition<?>> definitions,
        SpecialistRegistry specialists
    ) {
        return new DefaultSpecialistChainRegistry(
            definitions,
            specialists,
            clientFactory(),
            new CanonicalJsonSupport(OBJECT_MAPPER),
            Duration.ofMinutes(2),
            4,
            4,
            3,
            1,
            12_000
        );
    }

    private SpecialistChainDefinition<String> definition(
        String healthDescription,
        SpecialistChainLimits limits
    ) {
        return new SpecialistChainDefinition<>(
            SpecialistChainId.of("incident-investigation", "1"),
            MANAGER,
            String.class,
            chainInput(),
            List.of(healthTarget(healthDescription), changeTarget()),
            limits,
            SpecialistChainConversationPolicy.REQUIRED
        );
    }

    private SpecialistChainLimits conservativeLimits() {
        return new SpecialistChainLimits(
            Duration.ofSeconds(70),
            4,
            2,
            2,
            1,
            8_000
        );
    }

    private SpecialistChainTarget<String, String, Integer> healthTarget(
        String description
    ) {
        return new SpecialistChainTarget<>(
            HEALTH,
            description,
            mapper(String.class, String.class, "health-input"),
            projector(String.class, Integer.class, "health-result"),
            true,
            true,
            false
        );
    }

    private SpecialistChainTarget<String, String, Integer> changeTarget() {
        return new SpecialistChainTarget<>(
            CHANGE,
            "Inspect recent approved changes.",
            mapper(String.class, String.class, "change-input"),
            projector(String.class, Integer.class, "change-result"),
            true,
            true,
            true
        );
    }

    private SpecialistChainInputAdapter<String> chainInput() {
        return new SpecialistChainInputAdapter<>() {
            @Override
            public SpecialistChainComponentId id() {
                return SpecialistChainComponentId.of("incident-input", "1");
            }

            @Override
            public Class<String> inputType() {
                return String.class;
            }

            @Override
            public String currentUserMessage(String input) {
                return input;
            }
        };
    }

    private <P, I> SpecialistChainTargetInputMapper<P, I> mapper(
        Class<P> requestType,
        Class<I> inputType,
        String name
    ) {
        return new SpecialistChainTargetInputMapper<>() {
            @Override
            public SpecialistChainComponentId id() {
                return SpecialistChainComponentId.of(name, "1");
            }

            @Override
            public Class<P> chainRequestType() {
                return requestType;
            }

            @Override
            public Class<I> targetInputType() {
                return inputType;
            }

            @Override
            public I map(
                P chainRequest,
                SpecialistChainTargetRequest targetRequest
            ) {
                return inputType.cast(chainRequest);
            }
        };
    }

    private <P, O> SpecialistChainTargetResultProjector<P, O> projector(
        Class<P> requestType,
        Class<O> outputType,
        String name
    ) {
        return new SpecialistChainTargetResultProjector<>() {
            @Override
            public SpecialistChainComponentId id() {
                return SpecialistChainComponentId.of(name, "1");
            }

            @Override
            public Class<P> chainRequestType() {
                return requestType;
            }

            @Override
            public Class<O> targetOutputType() {
                return outputType;
            }

            @Override
            public SpecialistChainResultProjection project(
                P chainRequest,
                AIExecutionResult<O> targetExecution
            ) {
                return SpecialistChainResultProjection.summary(
                    String.valueOf(targetExecution.output())
                );
            }
        };
    }

    private SpecialistRegistry validSpecialists() {
        return specialists(
            manager(TARGETS, Set.of(CHANGE), directiveSchema(TARGETS)),
            worker(HEALTH, false, false),
            worker(CHANGE, false, false)
        );
    }

    private SpecialistDefinition<SpecialistChainManagerInput,
        SpecialistChainDirective> manager(
            List<SpecialistId> delegationTargets,
            Set<SpecialistId> handoffTargets,
            JsonNode schema
        ) {
        return manager(
            delegationTargets,
            handoffTargets,
            schema,
            SpecialistInteractionCapability.DIALOGUE_CAPABLE,
            SpecialistConversationBinding.REQUIRED,
            false,
            false
        );
    }

    private SpecialistDefinition<SpecialistChainManagerInput,
        SpecialistChainDirective> manager(
            List<SpecialistId> delegationTargets,
            Set<SpecialistId> handoffTargets,
            JsonNode schema,
            SpecialistInteractionCapability interaction,
            SpecialistConversationBinding binding,
            boolean recordTurns,
            boolean capabilities
        ) {
        return new SpecialistDefinition<>(
            new SpecialistIdentity(
                MANAGER,
                "Incident chain manager",
                "Selects bounded incident specialists."
            ),
            new SpecialistInstructions("Select one valid directive.", null),
            profile(capabilities),
            SpecialistLimits.defaults(),
            SpecialistDelegationPolicy.oneLevel(Set.copyOf(
                delegationTargets
            )),
            SpecialistHandoffPolicy.oneLevel(handoffTargets),
            input(
                SpecialistChainManagerInput.class,
                binding,
                recordTurns,
                interaction
            ),
            directiveOutput(schema)
        );
    }

    private SpecialistDefinition<String, Integer> worker(
        SpecialistId id,
        boolean write,
        boolean interactive
    ) {
        return new SpecialistDefinition<>(
            new SpecialistIdentity(
                id,
                id.name(),
                "Reads approved incident evidence."
            ),
            new SpecialistInstructions("Inspect evidence.", null),
            profile(write),
            SpecialistLimits.defaults(),
            SpecialistDelegationPolicy.disabled(),
            SpecialistHandoffPolicy.disabled(),
            input(
                String.class,
                interactive
                    ? SpecialistConversationBinding.REQUIRED
                    : SpecialistConversationBinding.DISABLED,
                false,
                interactive
                    ? SpecialistInteractionCapability.DIALOGUE_CAPABLE
                    : SpecialistInteractionCapability.NON_INTERACTIVE
            ),
            plainOutput(Integer.class)
        );
    }

    private SpecialistExecutionProfile profile(boolean capabilities) {
        Set<String> visible = capabilities
            ? Set.of("read_incident", "write_incident")
            : Set.of();
        RequestedCapabilityProfile requested = new RequestedCapabilityProfile(
            capabilities,
            capabilities ? Set.of("incident") : Set.of(),
            visible,
            capabilities ? Set.of("read_incident") : Set.of(),
            capabilities ? Set.of("write_incident") : Set.of()
        );
        return new SpecialistExecutionProfile(
            "test",
            requested,
            ExecutionStrategy.SINGLE_PASS,
            capabilities
                ? SpecialistWritePolicy.CONFIRMATION_RECEIPT_REQUIRED
                : SpecialistWritePolicy.DISABLED
        );
    }

    private <I> SpecialistInputAdapter<I> input(
        Class<I> type,
        SpecialistConversationBinding binding,
        boolean recordTurns,
        SpecialistInteractionCapability interaction
    ) {
        return new SpecialistInputAdapter<>() {
            @Override
            public Class<I> inputType() {
                return type;
            }

            @Override
            public void validate(I value) {}

            @Override
            public String renderModelInput(I value) {
                return value.toString();
            }

            @Override
            public String conversationInput(I value) {
                return value.toString();
            }

            @Override
            public OrchestrationContext orchestrationContext(I value) {
                return OrchestrationContext.builder().build();
            }

            @Override
            public SpecialistConversationBinding conversationBinding() {
                return binding;
            }

            @Override
            public boolean recordValidatedTurns() {
                return recordTurns;
            }

            @Override
            public SpecialistInteractionCapability interactionCapability() {
                return interaction;
            }
        };
    }

    private SpecialistOutputAdapter<SpecialistChainDirective>
        directiveOutput(JsonNode schema) {
        return new SpecialistOutputAdapter<>() {
            @Override
            public Class<SpecialistChainDirective> outputType() {
                return SpecialistChainDirective.class;
            }

            @Override
            public JsonSchemaOutputContract outputContract() {
                return new JsonSchemaOutputContract(
                    new SpecialistSchemaId("chain-directive", "1"),
                    schema,
                    "Return one bounded directive."
                );
            }

            @Override
            public SpecialistChainDirective project(
                OrchestrationResult result,
                List<AIEvidenceReference> evidence
            ) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void validate(SpecialistChainDirective output) {}
        };
    }

    private <O> SpecialistOutputAdapter<O> plainOutput(Class<O> type) {
        return new SpecialistOutputAdapter<>() {
            @Override
            public Class<O> outputType() {
                return type;
            }

            @Override
            public O project(
                OrchestrationResult result,
                List<AIEvidenceReference> evidence
            ) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void validate(O output) {}
        };
    }

    private SpecialistRegistry specialists(
        SpecialistDefinition<?, ?>... definitions
    ) {
        Map<SpecialistId, RegisteredSpecialist> values =
            new LinkedHashMap<>();
        for (SpecialistDefinition<?, ?> definition : definitions) {
            values.put(
                definition.id(),
                new RegisteredSpecialist(
                    definition,
                    SpecialistDefinitionSource.JAVA,
                    CanonicalJsonSupport.sha256(
                        "definition:" + definition.id()
                    ),
                    "test:" + definition.id(),
                    Map.of()
                )
            );
        }
        return new SpecialistRegistry() {
            @Override
            public Optional<SpecialistDefinition<?, ?>> find(SpecialistId id) {
                return Optional.ofNullable(values.get(id))
                    .map(RegisteredSpecialist::definition);
            }

            @Override
            public List<SpecialistDefinition<?, ?>> list() {
                return values.values().stream()
                    .map(RegisteredSpecialist::definition)
                    .toList();
            }

            @Override
            public Optional<RegisteredSpecialist> findRegistered(
                SpecialistId id
            ) {
                return Optional.ofNullable(values.get(id));
            }
        };
    }

    private SpecialistClientFactory clientFactory() {
        return new SpecialistClientFactory() {
            @Override
            public <I, O> SpecialistClient<I, O> bind(
                SpecialistId specialistId,
                Class<I> inputType,
                Class<O> outputType
            ) {
                @SuppressWarnings("unchecked")
                SpecialistClient<I, O> client = mock(
                    SpecialistClient.class
                );
                return client;
            }
        };
    }

    private JsonNode directiveSchema(List<SpecialistId> targets) {
        var schema = OBJECT_MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putArray("required")
            .add("type")
            .add("targets")
            .add("message")
            .add("reason")
            .add("supportingResultIds");
        var properties = schema.putObject("properties");
        var types = properties.putObject("type").putArray("enum");
        for (SpecialistChainDirectiveType type
            : SpecialistChainDirectiveType.values()) {
            types.add(type.name());
        }
        var targetsProperty = properties.putObject("targets");
        targetsProperty.put("type", "array");
        var item = targetsProperty.putObject("items");
        item.put("type", "object");
        item.put("additionalProperties", false);
        item.putArray("required")
            .add("targetSpecialist")
            .add("objective");
        var targetProperties = item.putObject("properties");
        var targetValues = targetProperties
            .putObject("targetSpecialist")
            .putArray("enum");
        targets.forEach(target -> targetValues.add(target.toString()));
        targetProperties.putObject("objective").put("type", "string");
        properties.putObject("message").put("type", "string");
        properties.putObject("reason").put("type", "string");
        var resultIds = properties.putObject("supportingResultIds");
        resultIds.put("type", "array");
        resultIds.put("uniqueItems", true);
        resultIds.put("maxItems", SpecialistChainDirective.MAX_TARGETS);
        resultIds.putObject("items").put("type", "string");
        return schema;
    }
}
