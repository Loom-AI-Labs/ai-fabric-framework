package dev.aifabric.examples.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.execution.chain.SpecialistChainDirective;
import ai.fabric.execution.chain.SpecialistChainManagerInput;
import ai.fabric.execution.chain.SpecialistChainTarget;
import ai.fabric.execution.chain.SpecialistChainTargetRequest;
import ai.fabric.execution.chain.manifest.DefaultSpecialistChainManifestCompiler;
import ai.fabric.execution.chain.manifest.SpecialistChainCompilationContext;
import ai.fabric.execution.config.AIExecutionProperties;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionStatus;
import ai.fabric.execution.specialist.JsonSchemaOutputContract;
import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistDefinitionSource;
import ai.fabric.execution.specialist.SpecialistDelegationPolicy;
import ai.fabric.execution.specialist.SpecialistExecutionProfile;
import ai.fabric.execution.specialist.SpecialistHandoffPolicy;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.SpecialistInputAdapter;
import ai.fabric.execution.specialist.SpecialistOutputAdapter;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.execution.specialist.manifest.DefaultSpecialistManifestLoader;
import ai.fabric.execution.specialist.manifest.JsonSchemaSpecialistInputAdapter;
import ai.fabric.execution.specialist.manifest.JsonSchemaSpecialistOutputAdapter;
import ai.fabric.execution.specialist.manifest.SpecialistConversationBinding;
import ai.fabric.execution.specialist.manifest.SpecialistInteractionCapability;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaRegistry;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaValidator;
import ai.fabric.execution.specialist.manifest.SpecialistResourceMetadata;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDefinition;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDirection;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaId;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaSpec;
import ai.fabric.intent.orchestration.capability.RequestedCapabilityProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PublicDeclarativeChainManifestTest {

    private static final String HASH = "a".repeat(64);
    private static final SpecialistId MANAGER = SpecialistId.of(
        "consumer-support-manager",
        "1"
    );
    private static final SpecialistId WORKER = SpecialistId.of(
        "consumer-account-reader",
        "1"
    );

    private final ObjectMapper objectMapper = new ObjectMapper()
        .findAndRegisterModules();

    @Test
    void compilesPackagedManifestThroughPublicArtifacts() {
        AIExecutionProperties.Manifests manifestProperties =
            new AIExecutionProperties.Manifests();
        manifestProperties.setEnabled(true);
        manifestProperties.setLocations(List.of(
            "classpath*:ai-chains/*.yml"
        ));
        var resources = new DefaultSpecialistManifestLoader(objectMapper)
            .load(manifestProperties);

        assertThat(resources.chainManifests()).hasSize(1);

        SpecialistSchemaDefinition chainInput = schema(
            "consumer-support-chain-input",
            SpecialistSchemaDirection.INPUT,
            Map.of("question", "string", "accountReference", "string"),
            List.of("question", "accountReference")
        );
        SpecialistSchemaDefinition workerInput = schema(
            "consumer-account-reader-input",
            SpecialistSchemaDirection.INPUT,
            Map.of("question", "string", "objective", "string"),
            List.of("question", "objective")
        );
        SpecialistSchemaDefinition workerOutput = schema(
            "consumer-account-reader-output",
            SpecialistSchemaDirection.OUTPUT,
            Map.of("summary", "string", "status", "string"),
            List.of("summary", "status")
        );
        SpecialistJsonSchemaValidator schemaValidator =
            new SpecialistJsonSchemaValidator();
        SpecialistRegistry registry = registry(
            registeredManager(),
            registeredWorker(workerInput, workerOutput)
        );
        var context = new SpecialistChainCompilationContext(
            registry,
            mock(SpecialistClientFactory.class),
            new SpecialistJsonSchemaRegistry(
                List.of(chainInput, workerInput, workerOutput),
                schemaValidator
            ),
            schemaValidator,
            new CanonicalJsonSupport(objectMapper),
            objectMapper,
            new AIExecutionProperties.SpecialistChains()
        );

        var registration = new DefaultSpecialistChainManifestCompiler()
            .compile(resources.chainManifests().getFirst(), context);

        assertThat(registration.definition().id().toString())
            .isEqualTo("consumer-support-investigation@1");
        assertThat(registration.identity().resourceHash())
            .hasValueSatisfying(hash -> assertThat(hash).matches("[a-f0-9]{64}"));
        assertThat(registration.identity().declarativeSemanticsHash())
            .hasValueSatisfying(hash -> assertThat(hash).matches("[a-f0-9]{64}"));
        assertThat(registration.definition().inputType())
            .isEqualTo(JsonNode.class);

        JsonNode request = objectMapper.createObjectNode()
            .put("question", "Why is account 17 blocked?")
            .put("accountReference", "account-17");
        @SuppressWarnings("unchecked")
        SpecialistChainTarget<JsonNode, JsonNode, JsonNode> target =
            (SpecialistChainTarget<JsonNode, JsonNode, JsonNode>)
                registration.definition().targets().getFirst();
        JsonNode workerInputValue = target.inputMapper().map(
            request,
            new SpecialistChainTargetRequest(
                WORKER.toString(),
                "Inspect approved account facts"
            )
        );

        assertThat(workerInputValue.path("question").asText())
            .isEqualTo("Why is account 17 blocked?");
        assertThat(workerInputValue.path("objective").asText())
            .isEqualTo("Inspect approved account facts");

        Instant now = Instant.parse("2026-09-19T00:00:00Z");
        AIExecutionResult<JsonNode> execution = new AIExecutionResult<>(
            "worker-1",
            WORKER,
            AIExecutionStatus.SUCCEEDED,
            objectMapper.createObjectNode()
                .put("summary", "The account is pending verification.")
                .put("status", "PENDING_VERIFICATION"),
            List.of(),
            Map.of(),
            null,
            now,
            now
        );
        var projection = target.resultProjector().project(
            request,
            execution
        );

        assertThat(projection.summary())
            .isEqualTo("The account is pending verification.");
        assertThat(projection.facts())
            .containsExactly(Map.entry("status", "PENDING_VERIFICATION"));
    }

    private SpecialistRegistry registry(
        RegisteredSpecialist manager,
        RegisteredSpecialist worker
    ) {
        return new SpecialistRegistry() {
            private final Map<SpecialistId, RegisteredSpecialist> values =
                Map.of(manager.id(), manager, worker.id(), worker);

            @Override
            public Optional<SpecialistDefinition<?, ?>> find(
                SpecialistId id
            ) {
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

            @Override
            public List<RegisteredSpecialist> listRegistered() {
                return List.copyOf(values.values());
            }
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private RegisteredSpecialist registeredManager() {
        SpecialistDefinition definition = mock(SpecialistDefinition.class);
        SpecialistInputAdapter<SpecialistChainManagerInput> input = mock(
            SpecialistInputAdapter.class
        );
        SpecialistOutputAdapter<SpecialistChainDirective> output = mock(
            SpecialistOutputAdapter.class
        );
        when(definition.id()).thenReturn(MANAGER);
        when(definition.executionProfile()).thenReturn(readOnlyProfile());
        when(input.inputType()).thenReturn(SpecialistChainManagerInput.class);
        when(input.interactionCapability()).thenReturn(
            SpecialistInteractionCapability.DIALOGUE_CAPABLE
        );
        when(input.conversationBinding()).thenReturn(
            SpecialistConversationBinding.REQUIRED
        );
        when(input.recordValidatedTurns()).thenReturn(false);
        when(input.inputContinuation()).thenReturn(Optional.empty());
        when(output.outputType()).thenReturn(SpecialistChainDirective.class);
        when(output.outputContract()).thenReturn(new JsonSchemaOutputContract(
            new SpecialistSchemaId("consumer-chain-directive", "1"),
            directiveSchema(),
            "Return one bounded directive."
        ));
        when(definition.inputAdapter()).thenReturn(input);
        when(definition.outputAdapter()).thenReturn(output);
        when(definition.delegationPolicy()).thenReturn(
            SpecialistDelegationPolicy.oneLevel(Set.of(WORKER))
        );
        when(definition.handoffPolicy()).thenReturn(
            SpecialistHandoffPolicy.disabled()
        );
        return registered(definition);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private RegisteredSpecialist registeredWorker(
        SpecialistSchemaDefinition inputSchema,
        SpecialistSchemaDefinition outputSchema
    ) {
        SpecialistDefinition definition = mock(SpecialistDefinition.class);
        JsonSchemaSpecialistInputAdapter input = mock(
            JsonSchemaSpecialistInputAdapter.class
        );
        JsonSchemaSpecialistOutputAdapter output = mock(
            JsonSchemaSpecialistOutputAdapter.class
        );
        when(definition.id()).thenReturn(WORKER);
        when(definition.executionProfile()).thenReturn(readOnlyProfile());
        when(input.schemaDefinition()).thenReturn(inputSchema);
        when(output.schemaDefinition()).thenReturn(outputSchema);
        when(input.inputType()).thenReturn(JsonNode.class);
        when(output.outputType()).thenReturn(JsonNode.class);
        when(input.interactionCapability()).thenReturn(
            SpecialistInteractionCapability.NON_INTERACTIVE
        );
        when(input.conversationBinding()).thenReturn(
            SpecialistConversationBinding.DISABLED
        );
        when(input.recordValidatedTurns()).thenReturn(false);
        when(input.inputContinuation()).thenReturn(Optional.empty());
        when(definition.inputAdapter()).thenReturn(input);
        when(definition.outputAdapter()).thenReturn(output);
        when(definition.delegationPolicy()).thenReturn(
            SpecialistDelegationPolicy.disabled()
        );
        when(definition.handoffPolicy()).thenReturn(
            SpecialistHandoffPolicy.disabled()
        );
        return registered(definition);
    }

    private RegisteredSpecialist registered(
        SpecialistDefinition<?, ?> definition
    ) {
        return new RegisteredSpecialist(
            definition,
            SpecialistDefinitionSource.MANIFEST,
            HASH,
            "consumer:" + definition.id(),
            Map.of()
        );
    }

    private SpecialistExecutionProfile readOnlyProfile() {
        return new SpecialistExecutionProfile(
            "consumer",
            new RequestedCapabilityProfile(
                false,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of()
            ),
            ai.fabric.execution.specialist.ExecutionStrategy.SINGLE_PASS,
            ai.fabric.execution.specialist.SpecialistWritePolicy.DISABLED
        );
    }

    private JsonNode directiveSchema() {
        var schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.set("required", objectMapper.createArrayNode()
            .add("type")
            .add("targets")
            .add("message")
            .add("reason")
            .add("supportingResultIds"));
        var properties = objectMapper.createObjectNode();
        properties.set("type", objectMapper.createObjectNode()
            .put("type", "string")
            .set("enum", objectMapper.createArrayNode()
                .add("ASK_USER")
                .add("INVOKE_ONE")
                .add("INVOKE_PARALLEL")
                .add("HANDOFF")
                .add("COMPLETE")));
        var target = objectMapper.createObjectNode();
        target.put("type", "object");
        target.put("additionalProperties", false);
        target.set("required", objectMapper.createArrayNode()
            .add("targetSpecialist")
            .add("objective"));
        var targetProperties = objectMapper.createObjectNode();
        targetProperties.set("targetSpecialist", objectMapper
            .createObjectNode()
            .put("type", "string")
            .set("enum", objectMapper.createArrayNode()
                .add(WORKER.toString())));
        targetProperties.set("objective", objectMapper.createObjectNode()
            .put("type", "string"));
        target.set("properties", targetProperties);
        properties.set("targets", objectMapper.createObjectNode()
            .put("type", "array")
            .set("items", target));
        properties.set("message", objectMapper.createObjectNode()
            .put("type", "string"));
        properties.set("reason", objectMapper.createObjectNode()
            .put("type", "string"));
        properties.set("supportingResultIds", objectMapper.createObjectNode()
            .put("type", "array")
            .put("uniqueItems", true)
            .put("maxItems", 8)
            .set("items", objectMapper.createObjectNode()
                .put("type", "string")));
        schema.set("properties", properties);
        return schema;
    }

    private SpecialistSchemaDefinition schema(
        String name,
        SpecialistSchemaDirection direction,
        Map<String, String> fields,
        List<String> required
    ) {
        var schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        var requiredNode = objectMapper.createArrayNode();
        required.forEach(requiredNode::add);
        schema.set("required", requiredNode);
        var properties = objectMapper.createObjectNode();
        fields.forEach((field, type) -> properties.set(
            field,
            objectMapper.createObjectNode().put("type", type)
        ));
        schema.set("properties", properties);
        return new SpecialistSchemaDefinition(
            "ai.fabric/v1",
            "SpecialistSchema",
            new SpecialistResourceMetadata(name, "1"),
            new SpecialistSchemaSpec(direction, "2020-12", schema)
        );
    }
}
