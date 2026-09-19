package ai.fabric.execution.chain.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.chain.SpecialistChainConversationPolicy;
import ai.fabric.execution.chain.SpecialistChainDirective;
import ai.fabric.execution.chain.SpecialistChainManagerInput;
import ai.fabric.execution.chain.SpecialistChainDefinitionSource;
import ai.fabric.execution.chain.SpecialistChainLimits;
import ai.fabric.execution.chain.SpecialistChainRegistration;
import ai.fabric.execution.chain.SpecialistChainTarget;
import ai.fabric.execution.chain.SpecialistChainTargetRequest;
import ai.fabric.execution.config.AIExecutionProperties;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionStatus;
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
import ai.fabric.execution.specialist.manifest.JsonSchemaSpecialistInputAdapter;
import ai.fabric.execution.specialist.manifest.JsonSchemaSpecialistOutputAdapter;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaRegistry;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaValidator;
import ai.fabric.execution.specialist.manifest.SpecialistManifestException;
import ai.fabric.execution.specialist.manifest.SpecialistResourceMetadata;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDefinition;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDirection;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaSpec;
import ai.fabric.execution.specialist.manifest.SpecialistConversationBinding;
import ai.fabric.execution.specialist.manifest.SpecialistInteractionCapability;
import ai.fabric.execution.specialist.JsonSchemaOutputContract;
import ai.fabric.intent.orchestration.capability.RequestedCapabilityProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultSpecialistChainManifestCompilerTest {

    private static final String HASH = "a".repeat(64);
    private static final SpecialistId MANAGER = SpecialistId.of(
        "support-manager",
        "1"
    );
    private static final SpecialistId WORKER = SpecialistId.of(
        "support-reader",
        "1"
    );

    private final ObjectMapper objectMapper = new ObjectMapper()
        .findAndRegisterModules();
    private final SpecialistJsonSchemaValidator schemaValidator =
        new SpecialistJsonSchemaValidator();
    private final DefaultSpecialistChainManifestCompiler compiler =
        new DefaultSpecialistChainManifestCompiler();

    private SpecialistChainCompilationContext context;
    private SpecialistRegistry specialistRegistry;

    @BeforeEach
    void setUp() {
        SpecialistSchemaDefinition chainInput = schema(
            "support-chain-input",
            SpecialistSchemaDirection.INPUT,
            Map.of(
                "question", "string",
                "accountReference", "string"
            ),
            List.of("question", "accountReference")
        );
        SpecialistSchemaDefinition workerInput = schema(
            "support-reader-input",
            SpecialistSchemaDirection.INPUT,
            Map.of("question", "string", "objective", "string"),
            List.of("question", "objective")
        );
        SpecialistSchemaDefinition workerOutput = schema(
            "support-reader-output",
            SpecialistSchemaDirection.OUTPUT,
            Map.of("summary", "string", "status", "string"),
            List.of("summary", "status")
        );
        specialistRegistry = mock(SpecialistRegistry.class);
        RegisteredSpecialist manager = registered(MANAGER, null, null);
        RegisteredSpecialist worker = registered(
            WORKER,
            workerInput,
            workerOutput
        );
        when(specialistRegistry.findRegistered(MANAGER))
            .thenReturn(Optional.of(manager));
        when(specialistRegistry.findRegistered(WORKER))
            .thenReturn(Optional.of(worker));
        when(specialistRegistry.listRegistered())
            .thenReturn(List.of(manager, worker));
        AIExecutionProperties.SpecialistChains deployment =
            new AIExecutionProperties.SpecialistChains();
        context = new SpecialistChainCompilationContext(
            specialistRegistry,
            mock(SpecialistClientFactory.class),
            new SpecialistJsonSchemaRegistry(
                List.of(chainInput, workerInput, workerOutput),
                schemaValidator
            ),
            schemaValidator,
            new CanonicalJsonSupport(objectMapper),
            objectMapper,
            deployment
        );
    }

    @Test
    void compilesAndExecutesBoundedJsonAdaptersWithoutJavaReferences() {
        SpecialistChainRegistration registration = compiler.compile(
            loaded(manifest("Support Investigation")),
            context
        );

        assertThat(registration.source())
            .isEqualTo(SpecialistChainDefinitionSource.MANIFEST);
        assertThat(registration.identity().resourceHash()).contains(HASH);
        assertThat(registration.identity().declarativeSemanticsHash())
            .hasValueSatisfying(value ->
                assertThat(value).matches("[a-f0-9]{64}")
            );
        assertThat(registration.identity().schemaDependencies())
            .extractingByKeys(
                new ai.fabric.execution.specialist.manifest.SpecialistSchemaId(
                    "support-chain-input",
                    "1"
                ),
                new ai.fabric.execution.specialist.manifest.SpecialistSchemaId(
                    "support-reader-input",
                    "1"
                ),
                new ai.fabric.execution.specialist.manifest.SpecialistSchemaId(
                    "support-reader-output",
                    "1"
                )
            )
            .allSatisfy(hash -> assertThat(hash).matches("[a-f0-9]{64}"));
        assertThat(registration.definition().inputType())
            .isEqualTo(JsonNode.class);

        JsonNode input = objectMapper.createObjectNode()
            .put("question", "Why is this ticket blocked?")
            .put("accountReference", "account-17");
        @SuppressWarnings("unchecked")
        var chainInput = (ai.fabric.execution.chain
            .SpecialistChainInputAdapter<JsonNode>) registration.definition()
                .inputAdapter();
        assertThat(chainInput.currentUserMessage(input))
            .isEqualTo("Why is this ticket blocked?");
        assertThat(chainInput.applicationContext(input))
            .extracting("name", "value")
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(
                    "accountReference",
                    "account-17"
                )
            );

        @SuppressWarnings("unchecked")
        SpecialistChainTarget<JsonNode, JsonNode, JsonNode> target =
            (SpecialistChainTarget<JsonNode, JsonNode, JsonNode>)
                registration.definition().targets().getFirst();
        JsonNode mapped = target.inputMapper().map(
            input,
            new SpecialistChainTargetRequest(
                WORKER.toString(),
                "Inspect approved support facts"
            )
        );
        assertThat(mapped.path("question").asText())
            .isEqualTo("Why is this ticket blocked?");
        assertThat(mapped.path("objective").asText())
            .isEqualTo("Inspect approved support facts");

        Instant now = Instant.parse("2026-09-19T00:00:00Z");
        AIExecutionResult<JsonNode> execution = new AIExecutionResult<>(
            "worker-1",
            WORKER,
            AIExecutionStatus.SUCCEEDED,
            objectMapper.createObjectNode()
                .put("summary", "The ticket awaits approval.")
                .put("status", "WAITING"),
            List.of(new AIEvidenceReference(
                "evidence-1",
                "Approved support evidence",
                0.91,
                "support",
                null,
                "support-policy",
                Map.of()
            )),
            Map.of(),
            null,
            now,
            now
        );
        var projection = target.resultProjector().project(input, execution);
        assertThat(projection.summary())
            .isEqualTo("The ticket awaits approval.");
        assertThat(projection.facts()).containsEntry("status", "WAITING");
        assertThat(projection.evidenceReferenceIds())
            .containsExactly("evidence-1");
    }

    @Test
    void descriptiveMetadataDoesNotChangeDeclarativeSemanticsHash() {
        SpecialistChainRegistration first = compiler.compile(
            loaded(manifest("Support Investigation")),
            context
        );
        SpecialistChainRegistration second = compiler.compile(
            new LoadedSpecialistChainManifest(
                manifest("Renamed For Operators"),
                "b".repeat(64),
                "renamed.yml#1"
            ),
            context
        );

        assertThat(second.identity().resourceHash())
            .isNotEqualTo(first.identity().resourceHash());
        assertThat(second.identity().declarativeSemanticsHash())
            .isEqualTo(first.identity().declarativeSemanticsHash());
    }

    @Test
    void offlineValidationRejectsDuplicatesAndExactVersionSemanticReuse() {
        SpecialistChainManifest original = manifest(
            "Support Investigation"
        );
        SpecialistChainRegistration published = compiler.compile(
            loaded(original),
            context
        );
        DefaultSpecialistChainManifestValidator validator =
            new DefaultSpecialistChainManifestValidator(compiler, context);

        var duplicate = validator.validate(
            List.of(loaded(original), loaded(original)),
            Map.of()
        );
        assertThat(duplicate.valid()).isFalse();
        assertThat(duplicate.diagnostics())
            .extracting("reason")
            .containsExactly("CHAIN_MANIFEST_DEFINITION_DUPLICATE");

        SpecialistChainManifest.Target target = original.spec().targets()
            .getFirst();
        SpecialistChainManifest changed = withTarget(
            original,
            new SpecialistChainManifest.Target(
                target.specialistRef(),
                "Reads a semantically different approved fact set.",
                target.input(),
                target.result(),
                target.transitions()
            )
        );
        var reused = validator.validate(
            List.of(loaded(changed)),
            Map.of(
                published.definition().id(),
                published.identity().declarativeSemanticsHash()
                    .orElseThrow()
            )
        );

        assertThat(reused.valid()).isFalse();
        assertThat(reused.diagnostics())
            .extracting("reason")
            .containsExactly("CHAIN_MANIFEST_EXACT_VERSION_REUSED");
    }

    @Test
    void authoringCatalogExposesSafeContractsWithoutBeanReferences() {
        AIExecutionProperties.SpecialistChains deployment =
            new AIExecutionProperties.SpecialistChains();
        deployment.setEnabled(true);

        SpecialistChainAuthoringCatalog catalog =
            new DefaultSpecialistChainAuthoringCatalogProvider(
                specialistRegistry,
                deployment
            ).catalog();

        assertThat(catalog.resourceContract()).isEqualTo("ai.fabric/v1");
        assertThat(catalog.featureEnabled()).isTrue();
        assertThat(catalog.specialists())
            .extracting("id")
            .containsExactly(MANAGER.toString(), WORKER.toString());
        assertThat(catalog.specialists())
            .filteredOn(option -> option.id().equals(WORKER.toString()))
            .singleElement()
            .satisfies(option -> {
                assertThat(option.readOnly()).isTrue();
                assertThat(option.nonInteractive()).isTrue();
                assertThat(option.schemaBackedJson()).isTrue();
                assertThat(option.inputSchema())
                    .isEqualTo("support-reader-input@1");
                assertThat(option.outputSchema())
                    .isEqualTo("support-reader-output@1");
            });
        assertThat(catalog.toString())
            .doesNotContain("bean", "class", "projectorRef", "mapperRef");
    }

    @Test
    void rejectsReservedDestinationFieldsBeforeAnyProviderCall() {
        SpecialistChainManifest valid = manifest("Support Investigation");
        SpecialistChainManifest.Target manifestTarget = valid.spec().targets()
            .getFirst();
        SpecialistChainManifest.MappingField invalid =
            new SpecialistChainManifest.MappingField(
                SpecialistChainMappingSource.CHAIN_INPUT,
                "/question",
                "__proto__",
                true
            );
        SpecialistChainManifest changed = withTarget(
            valid,
            new SpecialistChainManifest.Target(
                manifestTarget.specialistRef(),
                manifestTarget.description(),
                new SpecialistChainManifest.TargetInput(
                    manifestTarget.input().type(),
                    List.of(invalid)
                ),
                manifestTarget.result(),
                manifestTarget.transitions()
            )
        );

        assertThatThrownBy(() -> compiler.compile(loaded(changed), context))
            .isInstanceOf(SpecialistManifestException.class)
            .satisfies(error -> assertThat(
                ((SpecialistManifestException) error).reason()
            ).isEqualTo("CHAIN_MANIFEST_INPUT_MAPPING_INVALID"));
    }

    @Test
    void schemaRejectsMissingRequiredMappedInput() {
        SpecialistChainManifest valid = manifest("Support Investigation");
        SpecialistChainManifest.Target manifestTarget = valid.spec().targets()
            .getFirst();
        SpecialistChainManifest changed = withTarget(
            valid,
            new SpecialistChainManifest.Target(
                manifestTarget.specialistRef(),
                manifestTarget.description(),
                new SpecialistChainManifest.TargetInput(
                    manifestTarget.input().type(),
                    List.of(manifestTarget.input().fields().getFirst())
                ),
                manifestTarget.result(),
                manifestTarget.transitions()
            )
        );
        SpecialistChainRegistration registration = compiler.compile(
            loaded(changed),
            context
        );
        JsonNode input = objectMapper.createObjectNode()
            .put("question", "Inspect this")
            .put("accountReference", "account-17");
        @SuppressWarnings("unchecked")
        SpecialistChainTarget<JsonNode, JsonNode, JsonNode> compiledTarget =
            (SpecialistChainTarget<JsonNode, JsonNode, JsonNode>)
                registration.definition().targets().getFirst();

        assertThatThrownBy(() -> compiledTarget.inputMapper().map(
            input,
            new SpecialistChainTargetRequest(
                WORKER.toString(),
                "Inspect approved support facts"
            )
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("support-reader-input@1");
    }

    @Test
    void rejectsInvalidEnvelopeAndMissingExactDependenciesWithStableReasons() {
        SpecialistChainManifest valid = manifest("Support Investigation");

        assertCompileReason(
            new SpecialistChainManifest(
                "ai.fabric/v2",
                valid.kind(),
                valid.metadata(),
                valid.spec()
            ),
            "CHAIN_MANIFEST_API_VERSION_UNSUPPORTED"
        );
        assertCompileReason(
            new SpecialistChainManifest(
                valid.apiVersion(),
                "SpecialistGraph",
                valid.metadata(),
                valid.spec()
            ),
            "CHAIN_MANIFEST_KIND_UNSUPPORTED"
        );
        assertCompileReason(
            new SpecialistChainManifest(
                valid.apiVersion(),
                valid.kind(),
                new SpecialistChainManifest.Metadata(
                    "Invalid_Name",
                    "1",
                    valid.metadata().displayName(),
                    valid.metadata().description(),
                    valid.metadata().labels()
                ),
                valid.spec()
            ),
            "CHAIN_MANIFEST_ID_INVALID"
        );
        assertCompileReason(
            withSpec(
                valid,
                new SpecialistChainManifest.Input(
                    "missing-input@1",
                    valid.spec().input().managerMessagePointer(),
                    valid.spec().input().managerContext()
                ),
                valid.spec().manager(),
                valid.spec().targets()
            ),
            "CHAIN_MANIFEST_INPUT_SCHEMA_NOT_FOUND"
        );
        assertCompileReason(
            withSpec(
                valid,
                valid.spec().input(),
                new SpecialistChainManifest.Manager("missing-manager@1"),
                valid.spec().targets()
            ),
            "CHAIN_MANIFEST_MANAGER_NOT_FOUND"
        );
        SpecialistChainManifest.Target target = valid.spec().targets()
            .getFirst();
        assertCompileReason(
            withSpec(
                valid,
                valid.spec().input(),
                valid.spec().manager(),
                List.of(new SpecialistChainManifest.Target(
                    "missing-worker@1",
                    target.description(),
                    target.input(),
                    target.result(),
                    target.transitions()
                ))
            ),
            "CHAIN_MANIFEST_TARGET_NOT_FOUND"
        );
    }

    @Test
    void rejectsDuplicateSelfAndInvalidTransitionTargets() {
        SpecialistChainManifest valid = manifest("Support Investigation");
        SpecialistChainManifest.Target target = valid.spec().targets()
            .getFirst();

        assertCompileReason(
            withSpec(
                valid,
                valid.spec().input(),
                valid.spec().manager(),
                List.of(target, target)
            ),
            "CHAIN_MANIFEST_TARGET_DUPLICATE"
        );
        assertCompileReason(
            withSpec(
                valid,
                valid.spec().input(),
                new SpecialistChainManifest.Manager(WORKER.toString()),
                List.of(target)
            ),
            "CHAIN_MANIFEST_TARGET_BINDING_INVALID"
        );
        assertCompileReason(
            withTarget(
                valid,
                new SpecialistChainManifest.Target(
                    target.specialistRef(),
                    target.description(),
                    target.input(),
                    target.result(),
                    new SpecialistChainManifest.Transitions(
                        false,
                        true,
                        false
                    )
                )
            ),
            "CHAIN_MANIFEST_TARGET_BINDING_INVALID"
        );
    }

    private void assertCompileReason(
        SpecialistChainManifest manifest,
        String reason
    ) {
        assertThatThrownBy(() -> compiler.compile(loaded(manifest), context))
            .isInstanceOf(SpecialistManifestException.class)
            .satisfies(error -> assertThat(
                ((SpecialistManifestException) error).reason()
            ).isEqualTo(reason));
    }

    private SpecialistChainManifest withSpec(
        SpecialistChainManifest manifest,
        SpecialistChainManifest.Input input,
        SpecialistChainManifest.Manager manager,
        List<SpecialistChainManifest.Target> targets
    ) {
        SpecialistChainManifest.Spec spec = manifest.spec();
        return new SpecialistChainManifest(
            manifest.apiVersion(),
            manifest.kind(),
            manifest.metadata(),
            new SpecialistChainManifest.Spec(
                input,
                manager,
                targets,
                spec.limits(),
                spec.conversationPolicy()
            )
        );
    }

    private SpecialistChainManifest manifest(String displayName) {
        return new SpecialistChainManifest(
            "ai.fabric/v1",
            "SpecialistChain",
            new SpecialistChainManifest.Metadata(
                "support-investigation",
                "1",
                displayName,
                "Coordinates approved support readers.",
                Map.of("domain", "support")
            ),
            new SpecialistChainManifest.Spec(
                new SpecialistChainManifest.Input(
                    "support-chain-input@1",
                    "/question",
                    List.of(new SpecialistChainManifest.ContextValue(
                        "accountReference",
                        "/accountReference",
                        true
                    ))
                ),
                new SpecialistChainManifest.Manager(MANAGER.toString()),
                List.of(new SpecialistChainManifest.Target(
                    WORKER.toString(),
                    "Reads approved support facts.",
                    new SpecialistChainManifest.TargetInput(
                        SpecialistChainInputMappingType.JSON_POINTER_MAP,
                        List.of(
                            new SpecialistChainManifest.MappingField(
                                SpecialistChainMappingSource.CHAIN_INPUT,
                                "/question",
                                "question",
                                true
                            ),
                            new SpecialistChainManifest.MappingField(
                                SpecialistChainMappingSource.MANAGER_OBJECTIVE,
                                null,
                                "objective",
                                true
                            )
                        )
                    ),
                    new SpecialistChainManifest.TargetResult(
                        SpecialistChainResultProjectionType
                            .BOUNDED_FACT_PROJECTION,
                        "/summary",
                        List.of(new SpecialistChainManifest.FactField(
                            "status",
                            "/status",
                            true
                        )),
                        SpecialistChainEvidencePolicy.ALL_APPROVED
                    ),
                    new SpecialistChainManifest.Transitions(
                        true,
                        true,
                        false
                    )
                )),
                new SpecialistChainLimits(
                    Duration.ofSeconds(30),
                    3,
                    1,
                    1,
                    1,
                    4_000
                ),
                SpecialistChainConversationPolicy.REQUIRED
            )
        );
    }

    private SpecialistChainManifest withTarget(
        SpecialistChainManifest manifest,
        SpecialistChainManifest.Target target
    ) {
        var spec = manifest.spec();
        return new SpecialistChainManifest(
            manifest.apiVersion(),
            manifest.kind(),
            manifest.metadata(),
            new SpecialistChainManifest.Spec(
                spec.input(),
                spec.manager(),
                List.of(target),
                spec.limits(),
                spec.conversationPolicy()
            )
        );
    }

    private LoadedSpecialistChainManifest loaded(
        SpecialistChainManifest manifest
    ) {
        return new LoadedSpecialistChainManifest(
            manifest,
            HASH,
            "chain.yml#1"
        );
    }

    @SuppressWarnings("unchecked")
    private RegisteredSpecialist registered(
        SpecialistId id,
        SpecialistSchemaDefinition input,
        SpecialistSchemaDefinition output
    ) {
        SpecialistDefinition definition = mock(SpecialistDefinition.class);
        when(definition.id()).thenReturn(id);
        when(definition.executionProfile()).thenReturn(readOnlyProfile());
        if (input == null) {
            SpecialistInputAdapter<SpecialistChainManagerInput> managerInput =
                mock(SpecialistInputAdapter.class);
            SpecialistOutputAdapter<SpecialistChainDirective> managerOutput =
                mock(SpecialistOutputAdapter.class);
            when(managerInput.inputType())
                .thenReturn(SpecialistChainManagerInput.class);
            when(managerInput.interactionCapability()).thenReturn(
                SpecialistInteractionCapability.DIALOGUE_CAPABLE
            );
            when(managerInput.conversationBinding()).thenReturn(
                SpecialistConversationBinding.REQUIRED
            );
            when(managerInput.recordValidatedTurns()).thenReturn(false);
            when(managerInput.inputContinuation()).thenReturn(Optional.empty());
            when(managerOutput.outputType())
                .thenReturn(SpecialistChainDirective.class);
            when(managerOutput.outputContract()).thenReturn(
                new JsonSchemaOutputContract(
                    new ai.fabric.execution.specialist.manifest
                        .SpecialistSchemaId("chain-directive", "1"),
                    directiveSchema(),
                    "Return one bounded directive."
                )
            );
            when(definition.inputAdapter()).thenReturn(managerInput);
            when(definition.outputAdapter()).thenReturn(managerOutput);
            when(definition.delegationPolicy()).thenReturn(
                SpecialistDelegationPolicy.oneLevel(java.util.Set.of(WORKER))
            );
            when(definition.handoffPolicy()).thenReturn(
                SpecialistHandoffPolicy.disabled()
            );
        } else {
            JsonSchemaSpecialistInputAdapter inputAdapter = mock(
                JsonSchemaSpecialistInputAdapter.class
            );
            JsonSchemaSpecialistOutputAdapter outputAdapter = mock(
                JsonSchemaSpecialistOutputAdapter.class
            );
            when(inputAdapter.schemaDefinition()).thenReturn(input);
            when(outputAdapter.schemaDefinition()).thenReturn(output);
            when(inputAdapter.inputType()).thenReturn(JsonNode.class);
            when(outputAdapter.outputType()).thenReturn(JsonNode.class);
            when(inputAdapter.interactionCapability()).thenReturn(
                SpecialistInteractionCapability.NON_INTERACTIVE
            );
            when(inputAdapter.conversationBinding()).thenReturn(
                SpecialistConversationBinding.DISABLED
            );
            when(inputAdapter.recordValidatedTurns()).thenReturn(false);
            when(inputAdapter.inputContinuation()).thenReturn(Optional.empty());
            when(definition.inputAdapter()).thenReturn(inputAdapter);
            when(definition.outputAdapter()).thenReturn(outputAdapter);
            when(definition.delegationPolicy()).thenReturn(
                SpecialistDelegationPolicy.disabled()
            );
            when(definition.handoffPolicy()).thenReturn(
                SpecialistHandoffPolicy.disabled()
            );
        }
        return new RegisteredSpecialist(
            definition,
            SpecialistDefinitionSource.MANIFEST,
            HASH,
            "test:" + id,
            Map.of()
        );
    }

    private SpecialistExecutionProfile readOnlyProfile() {
        return new SpecialistExecutionProfile(
            "test",
            new RequestedCapabilityProfile(
                false,
                java.util.Set.of(),
                java.util.Set.of(),
                java.util.Set.of(),
                java.util.Set.of()
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
            .add("targetSpecialist").add("objective"));
        var targetProperties = objectMapper.createObjectNode();
        var targetSpecialist = objectMapper.createObjectNode();
        targetSpecialist.put("type", "string");
        targetSpecialist.set(
            "enum",
            objectMapper.createArrayNode().add(WORKER.toString())
        );
        targetProperties.set("targetSpecialist", targetSpecialist);
        targetProperties.set(
            "objective",
            objectMapper.createObjectNode().put("type", "string")
        );
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
