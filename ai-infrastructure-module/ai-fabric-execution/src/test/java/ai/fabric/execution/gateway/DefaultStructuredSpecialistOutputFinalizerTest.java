package ai.fabric.execution.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.specialist.ExecutionStrategy;
import ai.fabric.execution.specialist.JsonSchemaOutputContract;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistExecutionProfile;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.SpecialistIdentity;
import ai.fabric.execution.specialist.SpecialistInputAdapter;
import ai.fabric.execution.specialist.SpecialistInstructions;
import ai.fabric.execution.specialist.SpecialistLimits;
import ai.fabric.execution.specialist.SpecialistOutputAdapter;
import ai.fabric.execution.specialist.SpecialistOutputMode;
import ai.fabric.execution.specialist.SpecialistWritePolicy;
import ai.fabric.execution.specialist.manifest.DefaultSpecialistManifestCompiler;
import ai.fabric.execution.specialist.manifest.ManifestTestFixtures;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaId;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.capability.RequestedCapabilityProfile;
import ai.fabric.llm.structured.DefaultStructuredJsonCallExecutor;
import ai.fabric.llm.structured.StructuredJsonExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultStructuredSpecialistOutputFinalizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void finalizesBoundedGroundingIntoValidatedTypedOutput() {
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder()
                .content("""
                    {
                      "assessment": "BLOCKED",
                      "summary": "Payment is missing."
                    }
                    """)
                .model("gpt-test")
                .build());
        DefaultStructuredSpecialistOutputFinalizer finalizer = finalizer(
            aiCoreService
        );

        SpecialistOutputFinalization<TestOutput> finalized =
            finalizer.finalizeOutput(
                definition(),
                "Is this account ready?",
                OrchestrationContext.builder().build(),
                successfulResult(),
                List.of(evidence())
            );

        assertThat(finalized.output())
            .isEqualTo(new TestOutput("BLOCKED", "Payment is missing."));
        assertThat(finalized.diagnostics())
            .containsEntry("outputMode", "STRUCTURED_GENERATION")
            .containsEntry("outputFinalizationAttempts", 1)
            .containsEntry("outputFinalizationModel", "gpt-test")
            .containsEntry("groundingEvidenceCount", 1);

        ArgumentCaptor<AIGenerationRequest> request =
            ArgumentCaptor.forClass(AIGenerationRequest.class);
        org.mockito.Mockito.verify(aiCoreService)
            .generateContent(request.capture(), eq(LlmPurpose.GENERATION));
        assertThat(request.getValue().getSystemPrompt())
            .contains("untrusted data")
            .contains("schema-validated application input")
            .contains("requested")
            .contains("operation and supplied parameters")
            .contains("never grants")
            .contains("Follow the server-owned specialist instructions")
            .contains("only supplied grounding for claims")
            .contains("Do not infer a domain operation solely from names")
            .contains("silently substituted with a different approved operation")
            .contains("READ_ACTION_FACTS")
            .contains("authoritative server-produced application state")
            .contains("a requirement alone")
            .contains("Return exactly one JSON object");
        assertThat(request.getValue().getTemperature()).isZero();
        assertThat(request.getValue().getPrompt())
            .contains("Is this account ready?")
            .contains("Current account has no verified payment method.")
            .contains("A verified payment method is required.")
            .contains("BLOCKED or READY")
            .doesNotContain("internalNote");
    }

    @Test
    void regeneratesOneInvalidStructuredOutputWithoutFallback() {
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(), eq(LlmPurpose.GENERATION)))
            .thenReturn(
                AIGenerationResponse.builder()
                    .content("""
                        {
                          "assessment": "MAYBE",
                          "summary": "This violates the approved enum."
                        }
                        """)
                    .build(),
                AIGenerationResponse.builder()
                    .content("""
                        {
                          "assessment": "BLOCKED",
                          "summary": "Payment is missing."
                        }
                        """)
                    .model("gpt-test")
                    .build()
            );

        SpecialistOutputFinalization<TestOutput> finalized =
            finalizer(aiCoreService).finalizeOutput(
                definition(),
                "Is this account ready?",
                OrchestrationContext.builder().build(),
                successfulResult(),
                List.of(evidence())
            );

        assertThat(finalized.output())
            .isEqualTo(new TestOutput("BLOCKED", "Payment is missing."));
        assertThat(finalized.diagnostics())
            .containsEntry("outputFinalizationAttempts", 2)
            .containsEntry("outputFinalizationCorrected", true);
        ArgumentCaptor<AIGenerationRequest> requests =
            ArgumentCaptor.forClass(AIGenerationRequest.class);
        org.mockito.Mockito.verify(aiCoreService, times(2))
            .generateContent(requests.capture(), eq(LlmPurpose.GENERATION));
        assertThat(requests.getAllValues().get(1).getPrompt())
            .contains("BOUNDED STRUCTURED-OUTPUT CORRECTION")
            .contains("violated the schema or application validator")
            .contains("Do not add a fallback answer");
    }

    @Test
    void exposesProviderFailureWithoutFallback() {
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(), eq(LlmPurpose.GENERATION)))
            .thenThrow(new IllegalStateException("provider unavailable"));

        assertThatThrownBy(() -> finalizer(aiCoreService).finalizeOutput(
            definition(),
            "Is this account ready?",
            OrchestrationContext.builder().build(),
            successfulResult(),
            List.of(evidence())
        ))
            .isInstanceOfSatisfying(
                SpecialistOutputFinalizationException.class,
                failure -> {
                    assertThat(failure.reason())
                        .isEqualTo("OUTPUT_FINALIZATION_PROVIDER_FAILED");
                    assertThat(failure.retryable()).isTrue();
                }
            );
    }

    @Test
    void exposesNonJsonProviderOutputAfterBoundedRegeneration() {
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder()
                .content("The account looks blocked.")
                .build());

        assertThatThrownBy(() -> finalizer(aiCoreService).finalizeOutput(
            definition(),
            "Is this account ready?",
            OrchestrationContext.builder().build(),
            successfulResult(),
            List.of(evidence())
        ))
            .isInstanceOfSatisfying(
                SpecialistOutputFinalizationException.class,
                failure -> {
                    assertThat(failure.reason())
                        .isEqualTo("OUTPUT_FINALIZATION_NO_JSON");
                    assertThat(failure.retryable()).isFalse();
                }
            );
    }

    @Test
    void finalizesManifestJsonSchemaOutputWithoutAJavaDto() {
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder()
                .content("{\"answer\":\"Use approved recovery.\"}")
                .model("gpt-test")
                .build());

        SpecialistOutputFinalization<JsonNode> finalized =
            finalizer(aiCoreService).finalizeOutput(
                manifestDefinition(),
                "How do I recover access?",
                OrchestrationContext.builder().build(),
                successfulResult(),
                List.of(evidence())
            );

        assertThat(finalized.output().path("answer").textValue())
            .isEqualTo("Use approved recovery.");
        assertThat(finalized.diagnostics())
            .containsEntry("outputMode", "STRUCTURED_GENERATION");
    }

    @Test
    void exposesManifestSchemaMismatchAfterBoundedRegeneration() {
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder()
                .content(
                    "{\"answer\":\"Use approved recovery.\","
                        + "\"internal\":\"not allowed\"}"
                )
                .build());

        assertThatThrownBy(() -> finalizer(aiCoreService).finalizeOutput(
            manifestDefinition(),
            "How do I recover access?",
            OrchestrationContext.builder().build(),
            successfulResult(),
            List.of(evidence())
        ))
            .isInstanceOfSatisfying(
                SpecialistOutputFinalizationException.class,
                failure -> {
                    assertThat(failure.reason())
                        .isEqualTo("OUTPUT_FINALIZATION_VALIDATION_FAILED");
                    assertThat(failure.retryable()).isFalse();
                }
            );
    }

    @Test
    void givesRegenerationOnlyTheSanitizedSchemaLocation() {
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(), eq(LlmPurpose.GENERATION)))
            .thenReturn(
                AIGenerationResponse.builder()
                    .content(
                        "{\"answer\":\"Use approved recovery.\","
                            + "\"internal\":\"private-value\"}"
                    )
                    .build(),
                AIGenerationResponse.builder()
                    .content("{\"answer\":\"Use approved recovery.\"}")
                    .build()
            );

        SpecialistOutputFinalization<JsonNode> finalized =
            finalizer(aiCoreService).finalizeOutput(
                manifestDefinition(),
                "How do I recover access?",
                OrchestrationContext.builder().build(),
                successfulResult(),
                List.of(evidence())
            );

        assertThat(finalized.output().path("answer").asText())
            .isEqualTo("Use approved recovery.");
        ArgumentCaptor<AIGenerationRequest> requests =
            ArgumentCaptor.forClass(AIGenerationRequest.class);
        org.mockito.Mockito.verify(aiCoreService, times(2))
            .generateContent(requests.capture(), eq(LlmPurpose.GENERATION));
        assertThat(requests.getAllValues().get(1).getPrompt())
            .contains("The rejected schema location is /")
            .doesNotContain("private-value");
    }

    @Test
    void givesChainDirectiveRetryShapeWithoutEchoingRejectedContent() {
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(), eq(LlmPurpose.GENERATION)))
            .thenReturn(
                AIGenerationResponse.builder()
                    .content("""
                        {
                          "type": "INVOKE_ONE",
                          "targets": [{
                            "targetSpecialist": "account-reader@1",
                            "objective": "Inspect readiness."
                          }],
                          "message": null,
                          "reason": "Inspect the account.",
                          "supportingResultIds": ["private-result-id"]
                        }
                        """)
                    .build(),
                AIGenerationResponse.builder()
                    .content("""
                        {
                          "type": "INVOKE_ONE",
                          "targets": [{
                            "targetSpecialist": "account-reader@1",
                            "objective": "Inspect readiness."
                          }],
                          "message": null,
                          "reason": "Inspect the account.",
                          "supportingResultIds": []
                        }
                        """)
                    .build()
            );

        SpecialistOutputFinalization<JsonNode> finalized =
            finalizer(aiCoreService).finalizeOutput(
                chainDirectiveDefinition(),
                "Inspect my account.",
                OrchestrationContext.builder().build(),
                successfulResult(),
                List.of()
            );

        assertThat(finalized.output().path("type").asText())
            .isEqualTo("INVOKE_ONE");
        assertThat(finalized.output().path("supportingResultIds")).isEmpty();
        ArgumentCaptor<AIGenerationRequest> requests =
            ArgumentCaptor.forClass(AIGenerationRequest.class);
        org.mockito.Mockito.verify(aiCoreService, times(2))
            .generateContent(requests.capture(), eq(LlmPurpose.GENERATION));
        assertThat(requests.getAllValues().get(1).getPrompt())
            .contains("For a bounded chain directive")
            .contains("INVOKE_ONE and HANDOFF have exactly one target")
            .contains("supportingResultIds=[]")
            .doesNotContain("private-result-id");
    }

    private DefaultStructuredSpecialistOutputFinalizer finalizer(
        AICoreService aiCoreService
    ) {
        return new DefaultStructuredSpecialistOutputFinalizer(
            aiCoreService,
            new DefaultStructuredJsonCallExecutor(
                new StructuredJsonExtractor(),
                objectMapper
            ),
            objectMapper,
            new SpecialistGroundingProjector()
        );
    }

    private SpecialistDefinition<TestInput, TestOutput> definition() {
        return new SpecialistDefinition<>(
            new SpecialistIdentity(
                SpecialistId.of("account-resolver", "1"),
                "Account Resolver",
                "Evaluates current account readiness"
            ),
            new SpecialistInstructions(
                "Evaluate current account readiness.",
                "Use only current profile and policy evidence."
            ),
            new SpecialistExecutionProfile(
                "resolver",
                new RequestedCapabilityProfile(
                    false,
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of()
                ),
                ExecutionStrategy.SINGLE_PASS,
                SpecialistWritePolicy.DISABLED
            ),
            new SpecialistLimits(Duration.ofSeconds(30), 2_000, 3_000, 4),
            new SpecialistInputAdapter<>() {
                @Override
                public Class<TestInput> inputType() {
                    return TestInput.class;
                }

                @Override
                public void validate(TestInput input) {}

                @Override
                public String renderModelInput(TestInput input) {
                    return input.question();
                }
            },
            new SpecialistOutputAdapter<>() {
                @Override
                public Class<TestOutput> outputType() {
                    return TestOutput.class;
                }

                @Override
                public SpecialistOutputMode outputMode() {
                    return SpecialistOutputMode.STRUCTURED_GENERATION;
                }

                @Override
                public String outputContractInstructions() {
                    return "assessment must be BLOCKED or READY";
                }

                @Override
                public TestOutput project(
                    OrchestrationResult result,
                    List<AIEvidenceReference> evidence
                ) {
                    return new TestOutput("UNUSED", "UNUSED");
                }

                @Override
                public void validate(TestOutput output) {
                    if (!Set.of("BLOCKED", "READY")
                        .contains(output.assessment())) {
                        throw new IllegalArgumentException(
                            "assessment is invalid"
                        );
                    }
                    if (output.summary() == null || output.summary().isBlank()) {
                        throw new IllegalArgumentException(
                            "summary is required"
                        );
                    }
                }
            }
        );
    }

    private SpecialistDefinition<JsonNode, JsonNode>
        chainDirectiveDefinition() {
        var properties = objectMapper.createObjectNode();
        properties.set("type", objectMapper.createObjectNode());
        properties.set("targets", objectMapper.createObjectNode());
        properties.set("message", objectMapper.createObjectNode());
        properties.set("reason", objectMapper.createObjectNode());
        properties.set(
            "supportingResultIds",
            objectMapper.createObjectNode()
        );
        var schema = objectMapper.createObjectNode();
        schema.set("properties", properties);

        return new SpecialistDefinition<>(
            new SpecialistIdentity(
                SpecialistId.of("chain-manager", "1"),
                "Chain Manager",
                "Selects one approved worker"
            ),
            new SpecialistInstructions(
                "Select one approved worker.",
                "Return one bounded directive."
            ),
            new SpecialistExecutionProfile(
                "resolver",
                new RequestedCapabilityProfile(
                    false,
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of()
                ),
                ExecutionStrategy.SINGLE_PASS,
                SpecialistWritePolicy.DISABLED
            ),
            new SpecialistLimits(Duration.ofSeconds(30), 2_000, 3_000, 4),
            new SpecialistInputAdapter<>() {
                @Override
                public Class<JsonNode> inputType() {
                    return JsonNode.class;
                }

                @Override
                public void validate(JsonNode input) {}

                @Override
                public String renderModelInput(JsonNode input) {
                    return input.toString();
                }
            },
            new SpecialistOutputAdapter<>() {
                @Override
                public Class<JsonNode> outputType() {
                    return JsonNode.class;
                }

                @Override
                public SpecialistOutputMode outputMode() {
                    return SpecialistOutputMode.STRUCTURED_GENERATION;
                }

                @Override
                public JsonSchemaOutputContract outputContract() {
                    return new JsonSchemaOutputContract(
                        new SpecialistSchemaId("chain-directive", "1"),
                        schema,
                        "Return one bounded chain directive."
                    );
                }

                @Override
                public JsonNode project(
                    OrchestrationResult result,
                    List<AIEvidenceReference> evidence
                ) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void validate(JsonNode output) {
                    if ("INVOKE_ONE".equals(output.path("type").asText())
                        && !output.path("supportingResultIds").isEmpty()) {
                        throw new IllegalArgumentException(
                            "supporting results are invalid at "
                                + "/supportingResultIds"
                        );
                    }
                }
            }
        );
    }

    @SuppressWarnings("unchecked")
    private SpecialistDefinition<JsonNode, JsonNode> manifestDefinition() {
        return (SpecialistDefinition<JsonNode, JsonNode>)
            new DefaultSpecialistManifestCompiler()
                .compile(
                    ManifestTestFixtures.manifest(),
                    ManifestTestFixtures.compilationContext()
                )
                .specialist()
                .definition();
    }

    private OrchestrationResult successfulResult() {
        return OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .message("Current account has no verified payment method.")
            .build();
    }

    private AIEvidenceReference evidence() {
        return new AIEvidenceReference(
            "policy-payment",
            "A verified payment method is required.",
            0.97,
            "policy-catalog",
            null,
            "account-policy",
            Map.of("internalNote", "not-for-the-model")
        );
    }

    private record TestInput(String question) {}

    private record TestOutput(String assessment, String summary) {}
}
