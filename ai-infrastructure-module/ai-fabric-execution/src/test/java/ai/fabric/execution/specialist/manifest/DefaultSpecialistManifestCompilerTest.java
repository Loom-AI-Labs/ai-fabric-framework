package ai.fabric.execution.specialist.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.specialist.JsonSchemaOutputContract;
import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.SpecialistDefinitionSource;
import ai.fabric.execution.specialist.SpecialistOutputMode;
import ai.fabric.execution.input.SpecialistInputContinuation;
import ai.fabric.execution.input.SpecialistInputRequirement;
import ai.fabric.intent.orchestration.request.OrchestrationIntentPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultSpecialistManifestCompilerTest {

    private final DefaultSpecialistManifestCompiler compiler =
        new DefaultSpecialistManifestCompiler();

    @Test
    void compilesConfigurationOnlySpecialistIntoExistingDefinitionPath() {
        SpecialistCompilationResult result = compiler.compile(
            ManifestTestFixtures.manifest(),
            ManifestTestFixtures.compilationContext()
        );

        assertThat(result.specialist().source())
            .isEqualTo(SpecialistDefinitionSource.MANIFEST);
        assertThat(result.specialist().contentHash())
            .isEqualTo(ManifestTestFixtures.HASH);
        assertThat(result.specialist().definition().id().toString())
            .isEqualTo("support-knowledge@1");
        assertThat(result.specialist().definition().inputAdapter().inputType())
            .isEqualTo(JsonNode.class);
        assertThat(result.specialist().definition().inputAdapter()
            .interactionCapability())
            .isEqualTo(
                SpecialistInteractionCapability.NON_INTERACTIVE
            );
        assertThat(result.specialist().definition().outputAdapter().outputMode())
            .isEqualTo(SpecialistOutputMode.STRUCTURED_GENERATION);
        assertThat(result.specialist().definition()
            .outputAdapter().outputContract())
            .isInstanceOf(JsonSchemaOutputContract.class);
        assertThat(result.specialist().definition().delegationPolicy().enabled())
            .isFalse();
        assertThat(result.specialist().definition().handoffPolicy().enabled())
            .isFalse();
        assertThat(result.specialist().definition().outputAdapter()
            .orchestrationIntentPolicy())
            .isEqualTo(OrchestrationIntentPolicy.MODEL_DIRECTED);
    }

    @Test
    void compilesAnExplicitDialogueOwnerCapability() {
        SpecialistCompilationResult result = compiler.compile(
            withConversation(new SpecialistConversationSpec(
                SpecialistConversationBinding.REQUIRED,
                true,
                SpecialistInteractionCapability.DIALOGUE_CAPABLE
            )),
            ManifestTestFixtures.compilationContext()
        );

        assertThat(result.specialist().definition().inputAdapter()
            .interactionCapability())
            .isEqualTo(
                SpecialistInteractionCapability.DIALOGUE_CAPABLE
            );
        assertThat(result.specialist().definition().inputAdapter()
            .conversationBinding())
            .isEqualTo(SpecialistConversationBinding.REQUIRED);
        assertThat(result.specialist().definition().inputAdapter()
            .recordValidatedTurns()).isTrue();
        SpecialistCompilationResult ordinary = compiler.compile(
            ManifestTestFixtures.manifest(),
            ManifestTestFixtures.compilationContext()
        );
        assertThat(RegisteredSpecialist.javaDefinition(
            result.specialist().definition()
        ).contentHash()).isNotEqualTo(
            RegisteredSpecialist.javaDefinition(
                ordinary.specialist().definition()
            ).contentHash()
        );
    }

    @Test
    void rejectsDialogueCapabilityWithoutBinding() {
        assertThatThrownBy(() -> compiler.compile(
            withConversation(new SpecialistConversationSpec(
                SpecialistConversationBinding.DISABLED,
                false,
                SpecialistInteractionCapability.DIALOGUE_CAPABLE
            )),
            ManifestTestFixtures.compilationContext()
        ))
            .isInstanceOf(SpecialistManifestException.class)
            .satisfies(error -> assertThat(
                ((SpecialistManifestException) error).reason()
            ).isEqualTo("DIALOGUE_CAPABILITY_INVALID"));
    }

    @Test
    void compilesDialogueCapabilityThatDefersConversationRecording() {
        SpecialistCompilationResult result = compiler.compile(
            withConversation(new SpecialistConversationSpec(
                SpecialistConversationBinding.OPTIONAL,
                false,
                SpecialistInteractionCapability.DIALOGUE_CAPABLE
            )),
            ManifestTestFixtures.compilationContext()
        );

        assertThat(result.specialist().definition().inputAdapter()
            .interactionCapability())
            .isEqualTo(
                SpecialistInteractionCapability.DIALOGUE_CAPABLE
            );
        assertThat(result.specialist().definition().inputAdapter()
            .conversationBinding())
            .isEqualTo(SpecialistConversationBinding.OPTIONAL);
        assertThat(result.specialist().definition().inputAdapter()
            .recordValidatedTurns()).isFalse();
    }

    @Test
    void derivesStructuredOutputOnlyIntentPolicyFromClosedManifestContract() {
        SpecialistManifest valid = ManifestTestFixtures.manifest();
        SpecialistManifestSpec spec = valid.spec();
        SpecialistManifest generationOnly = new SpecialistManifest(
            valid.apiVersion(),
            valid.kind(),
            valid.metadata(),
            new SpecialistManifestSpec(
                spec.mode(),
                spec.instructions(),
                spec.execution(),
                new SpecialistCapabilitySpec(
                    new SpecialistRetrievalSpec(false, List.of()),
                    new SpecialistActionSpec(
                        List.of(),
                        List.of(),
                        List.of()
                    )
                ),
                spec.input(),
                new SpecialistGroundingSpec(
                    SpecialistGroundingRequirement.NONE,
                    false,
                    List.of(),
                    List.of()
                ),
                spec.output(),
                spec.conversation(),
                spec.limits(),
                spec.delegation()
            )
        );

        SpecialistCompilationResult result = compiler.compile(
            generationOnly,
            ManifestTestFixtures.compilationContext()
        );

        assertThat(result.specialist().definition().outputAdapter()
            .orchestrationIntentPolicy())
            .isEqualTo(OrchestrationIntentPolicy.STRUCTURED_OUTPUT_ONLY);
    }

    @Test
    void compilesExactDelegationTargetsIntoTheCanonicalDefinition() {
        SpecialistManifest valid = ManifestTestFixtures.manifest();
        SpecialistManifestSpec spec = valid.spec();
        SpecialistManifest delegated = new SpecialistManifest(
            valid.apiVersion(),
            valid.kind(),
            valid.metadata(),
            new SpecialistManifestSpec(
                spec.mode(),
                spec.instructions(),
                spec.execution(),
                spec.capabilities(),
                spec.input(),
                spec.grounding(),
                spec.output(),
                spec.conversation(),
                spec.limits(),
                new SpecialistDelegationSpec(List.of(
                    "account-profile-checker@1",
                    "billing-policy-checker@2"
                ))
            )
        );

        SpecialistCompilationResult result = compiler.compile(
            delegated,
            ManifestTestFixtures.compilationContext()
        );

        assertThat(result.specialist().definition()
            .delegationPolicy().allowedTargets())
            .containsExactlyInAnyOrder(
                SpecialistId.of("account-profile-checker", "1"),
                SpecialistId.of("billing-policy-checker", "2")
            );
    }

    @Test
    void rejectsDuplicateAndNonExactDelegationTargets() {
        assertThatThrownBy(() -> compiler.compile(
            withDelegation(List.of(
                "account-profile-checker@1",
                "account-profile-checker@1"
            )),
            ManifestTestFixtures.compilationContext()
        ))
            .isInstanceOf(SpecialistManifestException.class)
            .hasMessageContaining(
                "Duplicate values are not allowed in delegation targets"
            );

        assertThatThrownBy(() -> compiler.compile(
            withDelegation(List.of("account-profile-checker")),
            ManifestTestFixtures.compilationContext()
        ))
            .isInstanceOf(SpecialistManifestException.class)
            .satisfies(error -> assertThat(
                ((SpecialistManifestException) error).reason()
            ).isEqualTo("DELEGATION_TARGET_INVALID"));
    }

    @Test
    void compilesExactHandoffTargetsSeparatelyFromDelegation() {
        SpecialistCompilationResult result = compiler.compile(
            withHandoff(List.of(
                "account-profile-checker@1",
                "billing-policy-checker@2"
            )),
            ManifestTestFixtures.compilationContext()
        );

        assertThat(result.specialist().definition()
            .handoffPolicy().allowedTargets())
            .containsExactlyInAnyOrder(
                SpecialistId.of("account-profile-checker", "1"),
                SpecialistId.of("billing-policy-checker", "2")
            );
        assertThat(result.specialist().definition()
            .delegationPolicy().enabled()).isFalse();
    }

    @Test
    void rejectsDuplicateAndNonExactHandoffTargets() {
        assertThatThrownBy(() -> compiler.compile(
            withHandoff(List.of(
                "account-profile-checker@1",
                "account-profile-checker@1"
            )),
            ManifestTestFixtures.compilationContext()
        ))
            .isInstanceOf(SpecialistManifestException.class)
            .hasMessageContaining(
                "Duplicate values are not allowed in handoff targets"
            );

        assertThatThrownBy(() -> compiler.compile(
            withHandoff(List.of("account-profile-checker")),
            ManifestTestFixtures.compilationContext()
        ))
            .isInstanceOf(SpecialistManifestException.class)
            .satisfies(error -> assertThat(
                ((SpecialistManifestException) error).reason()
            ).isEqualTo("HANDOFF_TARGET_INVALID"));
    }

    @Test
    void unknownModeFailsWithStableSafeReason() {
        assertThatThrownBy(() -> compiler.compile(
            ManifestTestFixtures.manifest("support-knowledge", "missing"),
            ManifestTestFixtures.compilationContext()
        ))
            .isInstanceOf(SpecialistManifestException.class)
            .satisfies(error -> assertThat(
                ((SpecialistManifestException) error).reason()
            ).isEqualTo("MANIFEST_COMPILATION_FAILED"))
            .hasMessageContaining("unknown Mode");
    }

    @Test
    void unsupportedApiVersionFailsBeforeRegistration() {
        SpecialistManifest valid = ManifestTestFixtures.manifest();
        SpecialistManifest unsupported = new SpecialistManifest(
            "ai.fabric/v2",
            valid.kind(),
            valid.metadata(),
            valid.spec()
        );

        assertThatThrownBy(() -> compiler.compile(
            unsupported,
            ManifestTestFixtures.compilationContext()
        ))
            .isInstanceOf(SpecialistManifestException.class)
            .satisfies(error -> assertThat(
                ((SpecialistManifestException) error).reason()
            ).isEqualTo("RESOURCE_API_VERSION_UNSUPPORTED"));
    }

    @Test
    void compilesExactInputContinuationAndRegisteredResponseSchema() {
        SpecialistInputContinuation<JsonNode> continuation =
            jsonContinuation();
        SpecialistCompilationResult result = compiler.compile(
            withContinuation(
                ManifestTestFixtures.manifest(),
                continuation.id()
            ),
            ManifestTestFixtures.compilationContext(
                List.of(continuation),
                List.of(amountResponseSchema(
                    SpecialistSchemaDirection.INPUT
                ))
            )
        );

        assertThat(result.specialist().definition().inputAdapter()
            .inputContinuation())
            .hasValueSatisfying(value ->
                assertThat(value.id()).isEqualTo(continuation.id())
            );
    }

    @Test
    void rejectsUnknownContinuationBeforeRegistration() {
        assertThatThrownBy(() -> compiler.compile(
            withContinuation(
                ManifestTestFixtures.manifest(),
                "missing-continuation@1"
            ),
            ManifestTestFixtures.compilationContext()
        ))
            .isInstanceOf(SpecialistManifestException.class)
            .satisfies(error -> assertThat(
                ((SpecialistManifestException) error).reason()
            ).isEqualTo("EXTENSION_REFERENCE_NOT_FOUND"));
    }

    @Test
    void rejectsContinuationWhoseResponseSchemaIsMissingOrNotInput() {
        SpecialistInputContinuation<JsonNode> continuation =
            jsonContinuation();

        assertThatThrownBy(() -> compiler.compile(
            withContinuation(
                ManifestTestFixtures.manifest(),
                continuation.id()
            ),
            ManifestTestFixtures.compilationContext(
                List.of(continuation),
                List.of()
            )
        ))
            .isInstanceOf(SpecialistManifestException.class)
            .satisfies(error -> assertThat(
                ((SpecialistManifestException) error).reason()
            ).isEqualTo("SCHEMA_REFERENCE_NOT_FOUND"));
        assertThatThrownBy(() -> compiler.compile(
            withContinuation(
                ManifestTestFixtures.manifest(),
                continuation.id()
            ),
            ManifestTestFixtures.compilationContext(
                List.of(continuation),
                List.of(amountResponseSchema(
                    SpecialistSchemaDirection.OUTPUT
                ))
            )
        ))
            .isInstanceOf(SpecialistManifestException.class)
            .satisfies(error -> assertThat(
                ((SpecialistManifestException) error).reason()
            ).isEqualTo("SCHEMA_DIRECTION_MISMATCH"));
    }

    @Test
    void rejectsManifestContinuationWithNonJsonInputType() {
        SpecialistInputContinuation<String> continuation =
            new SpecialistInputContinuation<>() {
                @Override
                public String id() {
                    return "string-continuation@1";
                }

                @Override
                public Class<String> inputType() {
                    return String.class;
                }

                @Override
                public Set<SpecialistSchemaId> responseSchemas() {
                    return Set.of(AMOUNT_RESPONSE_SCHEMA);
                }

                @Override
                public Optional<SpecialistInputRequirement> requiredInput(
                    String input
                ) {
                    return Optional.empty();
                }

                @Override
                public String resume(
                    String originalInput,
                    SpecialistInputRequirement requirement,
                    JsonNode response
                ) {
                    return originalInput;
                }
            };

        assertThatThrownBy(() -> compiler.compile(
            withContinuation(
                ManifestTestFixtures.manifest(),
                continuation.id()
            ),
            ManifestTestFixtures.compilationContext(
                List.of(continuation),
                List.of(amountResponseSchema(
                    SpecialistSchemaDirection.INPUT
                ))
            )
        ))
            .isInstanceOf(SpecialistManifestException.class)
            .satisfies(error -> assertThat(
                ((SpecialistManifestException) error).reason()
            ).isEqualTo("INPUT_CONTINUATION_TYPE_MISMATCH"));
    }

    private static final SpecialistSchemaId AMOUNT_RESPONSE_SCHEMA =
        SpecialistSchemaId.parse("billing-amount-response@1");

    private SpecialistInputContinuation<JsonNode> jsonContinuation() {
        return new SpecialistInputContinuation<>() {
            @Override
            public String id() {
                return "billing-amount-input@1";
            }

            @Override
            public Class<JsonNode> inputType() {
                return JsonNode.class;
            }

            @Override
            public Set<SpecialistSchemaId> responseSchemas() {
                return Set.of(AMOUNT_RESPONSE_SCHEMA);
            }

            @Override
            public Optional<SpecialistInputRequirement> requiredInput(
                JsonNode input
            ) {
                return Optional.of(new SpecialistInputRequirement(
                    "MISSING_BILLING_AMOUNT",
                    "What amount should be assessed?",
                    AMOUNT_RESPONSE_SCHEMA,
                    Duration.ofMinutes(5),
                    2
                ));
            }

            @Override
            public JsonNode resume(
                JsonNode originalInput,
                SpecialistInputRequirement requirement,
                JsonNode response
            ) {
                var resumed = originalInput.deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) resumed)
                    .set("amount", response.required("amount"));
                return resumed;
            }

            @Override
            public JsonNode snapshot(JsonNode input) {
                return input.deepCopy();
            }
        };
    }

    private SpecialistSchemaDefinition amountResponseSchema(
        SpecialistSchemaDirection direction
    ) {
        var mapper = ManifestTestFixtures.objectMapper();
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.set("required", mapper.createArrayNode().add("amount"));
        schema.set(
            "properties",
            mapper.createObjectNode().set(
                "amount",
                mapper.createObjectNode()
                    .put("type", "number")
                    .put("exclusiveMinimum", 0)
            )
        );
        return new SpecialistSchemaDefinition(
            "ai.fabric/v1",
            "SpecialistSchema",
            new SpecialistResourceMetadata(
                AMOUNT_RESPONSE_SCHEMA.name(),
                AMOUNT_RESPONSE_SCHEMA.version()
            ),
            new SpecialistSchemaSpec(direction, "2020-12", schema)
        );
    }

    private SpecialistManifest withContinuation(
        SpecialistManifest manifest,
        String continuationRef
    ) {
        SpecialistManifestSpec spec = manifest.spec();
        SpecialistInputSpec input = spec.input();
        return new SpecialistManifest(
            manifest.apiVersion(),
            manifest.kind(),
            manifest.metadata(),
            new SpecialistManifestSpec(
                spec.mode(),
                spec.instructions(),
                spec.execution(),
                spec.capabilities(),
                new SpecialistInputSpec(
                    input.schemaRef(),
                    continuationRef,
                    input.rendering(),
                    input.primaryTextPointer(),
                    input.conversationTextPointer(),
                    input.contextPointers(),
                    input.context()
                ),
                spec.grounding(),
                spec.output(),
                spec.conversation(),
                spec.limits()
            )
        );
    }

    private SpecialistManifest withConversation(
        SpecialistConversationSpec conversation
    ) {
        SpecialistManifest manifest = ManifestTestFixtures.manifest();
        SpecialistManifestSpec spec = manifest.spec();
        return new SpecialistManifest(
            manifest.apiVersion(),
            manifest.kind(),
            manifest.metadata(),
            new SpecialistManifestSpec(
                spec.mode(),
                spec.instructions(),
                spec.execution(),
                spec.capabilities(),
                spec.input(),
                spec.grounding(),
                spec.output(),
                conversation,
                spec.limits(),
                spec.delegation(),
                spec.handoff()
            )
        );
    }

    private SpecialistManifest withDelegation(List<String> targets) {
        SpecialistManifest manifest = ManifestTestFixtures.manifest();
        SpecialistManifestSpec spec = manifest.spec();
        return new SpecialistManifest(
            manifest.apiVersion(),
            manifest.kind(),
            manifest.metadata(),
            new SpecialistManifestSpec(
                spec.mode(),
                spec.instructions(),
                spec.execution(),
                spec.capabilities(),
                spec.input(),
                spec.grounding(),
                spec.output(),
                spec.conversation(),
                spec.limits(),
                new SpecialistDelegationSpec(targets)
            )
        );
    }

    private SpecialistManifest withHandoff(List<String> targets) {
        SpecialistManifest manifest = ManifestTestFixtures.manifest();
        SpecialistManifestSpec spec = manifest.spec();
        return new SpecialistManifest(
            manifest.apiVersion(),
            manifest.kind(),
            manifest.metadata(),
            new SpecialistManifestSpec(
                spec.mode(),
                spec.instructions(),
                spec.execution(),
                spec.capabilities(),
                spec.input(),
                spec.grounding(),
                spec.output(),
                spec.conversation(),
                spec.limits(),
                SpecialistDelegationSpec.disabled(),
                new SpecialistHandoffSpec(targets)
            )
        );
    }
}
