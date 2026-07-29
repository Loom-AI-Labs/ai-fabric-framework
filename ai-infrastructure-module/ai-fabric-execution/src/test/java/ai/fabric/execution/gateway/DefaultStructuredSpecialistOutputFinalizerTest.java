package ai.fabric.execution.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.specialist.ExecutionStrategy;
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
    void exposesNonJsonProviderOutputWithoutFallback() {
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
    void exposesManifestSchemaMismatchWithoutRepairOrFallback() {
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
