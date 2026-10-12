package ai.fabric.intent.extraction;

import ai.fabric.config.ProgressiveIntentExtractionProperties;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.exception.AIServiceException;
import ai.fabric.intent.IntentExtractionPostProcessor;
import ai.fabric.intent.IntentExtractionValidator;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.policy.SemanticRecoveryPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProgressiveIntentExtractionEngineTest {

    @Test
    void preservesTypedFailureAndAllowsOnlyNamedMissingSpaceRecovery() {
        ProgressiveIntentExtractionProperties properties =
            new ProgressiveIntentExtractionProperties();
        properties.setForceMode("compound");
        properties.setMaxTotalLlmCalls(1);

        CompoundIntentExtractionStrategy compound =
            mock(CompoundIntentExtractionStrategy.class);
        RepairIntentExtractionStrategy repair =
            mock(RepairIntentExtractionStrategy.class);
        CompletionIntentExtractionStrategy completion =
            mock(CompletionIntentExtractionStrategy.class);
        MultiStepIntentExtractionStrategy multiStep =
            mock(MultiStepIntentExtractionStrategy.class);
        IntentExtractionPostProcessor postProcessor =
            mock(IntentExtractionPostProcessor.class);
        IntentExtractionValidator validator =
            mock(IntentExtractionValidator.class);

        MultiIntentResponse incomplete = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(IntentType.INFORMATION)
                .intent("grounded_answer")
                .requiresRetrieval(true)
                .requiresGeneration(true)
                .requiresTargetResolution(false)
                .needsAdvancedRAG(false)
                .build()))
            .build();
        ExtractionAttempt raw = ExtractionAttempt.builder()
            .success(true)
            .response(incomplete)
            .strategyName("compound")
            .llmCalls(1)
            .build();
        IntentExtractionValidator.ValidationIssue issue =
            new IntentExtractionValidator.ValidationIssue(
                IntentExtractionValidator.IssueCode.INFORMATION_MISSING_VECTOR_SPACE,
                IntentExtractionValidator.Severity.ERROR,
                0,
                "vectorSpace",
                "Missing vectorSpace decision"
            );
        IntentExtractionValidator.ValidationResult validation =
            new IntentExtractionValidator.ValidationResult(
                false,
                IntentExtractionValidator.ErrorCategory.INCOMPLETE,
                List.of(issue),
                List.of(issue.message()),
                List.of()
            );
        when(compound.attemptExtract(
            any(IntentExtractionInput.class),
            any(OrchestrationContext.class)
        )).thenReturn(raw);
        when(postProcessor.postProcess(any(MultiIntentResponse.class), anyString()))
            .thenReturn(incomplete);
        when(validator.validate(any(MultiIntentResponse.class), anyString()))
            .thenReturn(validation);

        ProgressiveIntentExtractionEngine engine =
            new ProgressiveIntentExtractionEngine(
                properties,
                compound,
                repair,
                completion,
                multiStep,
                postProcessor,
                validator
            );
        IntentExtractionInput input = new IntentExtractionInput("q", "q", List.of());
        OrchestrationContext context = OrchestrationContext.forUser("user");

        ProgressiveIntentExtractionEngine.ExtractionOutput strict =
            engine.extract(input, context, SemanticRecoveryPolicy.strictDefaults());

        assertThat(strict.response()).isNull();
        assertThat(strict.failure().issueCodes())
            .containsExactly(
                IntentExtractionValidator.IssueCode.INFORMATION_MISSING_VECTOR_SPACE
            );

        SemanticRecoveryPolicy boundedFanOut = new SemanticRecoveryPolicy(
            null,
            null,
            OrchestrationProperties.MissingVectorSpaceRecovery.BOUNDED_ALLOWED_FAN_OUT,
            null,
            null,
            null,
            null
        );
        ProgressiveIntentExtractionEngine.ExtractionOutput recovered =
            engine.extract(input, context, boundedFanOut);

        assertThat(recovered.failure()).isNull();
        assertThat(recovered.response()).isSameAs(incomplete);
        assertThat(recovered.diagnostics())
            .containsEntry("extractionPath", "policy_recovery");
        assertThat(semanticDecision(recovered))
            .containsEntry("decision", "VECTOR_SPACE_SELECTION")
            .containsEntry("recoveryPolicy", "BOUNDED_ALLOWED_FAN_OUT")
            .containsEntry(
                "effectiveValue",
                "DEFER_TO_BOUNDED_ALLOWED_FAN_OUT"
            )
            .containsEntry("outcome", "DEFERRED_TO_GOVERNED_PIPELINE_STEP");
    }

    @Test
    void defersRejectedVectorAndActionDomainDecisionsOnlyToTheirGovernedPolicies() {
        ProgressiveIntentExtractionProperties properties =
            new ProgressiveIntentExtractionProperties();
        properties.setForceMode("compound");
        properties.setMaxTotalLlmCalls(1);

        CompoundIntentExtractionStrategy compound =
            mock(CompoundIntentExtractionStrategy.class);
        RepairIntentExtractionStrategy repair =
            mock(RepairIntentExtractionStrategy.class);
        CompletionIntentExtractionStrategy completion =
            mock(CompletionIntentExtractionStrategy.class);
        MultiStepIntentExtractionStrategy multiStep =
            mock(MultiStepIntentExtractionStrategy.class);
        IntentExtractionPostProcessor postProcessor =
            mock(IntentExtractionPostProcessor.class);
        IntentExtractionValidator validator =
            mock(IntentExtractionValidator.class);

        MultiIntentResponse invalidVectorResponse = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(IntentType.INFORMATION)
                .intent("grounded_answer")
                .vectorSpace("not-allowed")
                .requiresRetrieval(true)
                .requiresGeneration(true)
                .requiresTargetResolution(false)
                .needsAdvancedRAG(false)
                .build()))
            .build();
        MultiIntentResponse actionMismatchResponse = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(IntentType.ACTION)
                .action("read_vehicle")
                .vectorSpace("document")
                .requiresRetrieval(true)
                .requiresGeneration(true)
                .requiresTargetResolution(false)
                .needsAdvancedRAG(false)
                .build()))
            .build();
        ExtractionAttempt invalidVectorAttempt = ExtractionAttempt.builder()
            .success(true)
            .response(invalidVectorResponse)
            .strategyName("compound")
            .llmCalls(1)
            .build();
        ExtractionAttempt actionMismatchAttempt = ExtractionAttempt.builder()
            .success(true)
            .response(actionMismatchResponse)
            .strategyName("compound")
            .llmCalls(1)
            .build();
        IntentExtractionValidator.ValidationIssue invalidVectorIssue =
            new IntentExtractionValidator.ValidationIssue(
                IntentExtractionValidator.IssueCode.INFORMATION_INVALID_VECTOR_SPACE,
                IntentExtractionValidator.Severity.ERROR,
                0,
                "vectorSpace",
                "Selected vector space is not allowed"
            );
        IntentExtractionValidator.ValidationIssue actionMismatchIssue =
            new IntentExtractionValidator.ValidationIssue(
                IntentExtractionValidator.IssueCode.ACTION_GROUNDING_VECTOR_SPACE_MISMATCH,
                IntentExtractionValidator.Severity.ERROR,
                0,
                "vectorSpace",
                "Selected vector space is outside the action evidence domain"
            );
        IntentExtractionValidator.ValidationResult invalidVectorValidation =
            new IntentExtractionValidator.ValidationResult(
                false,
                IntentExtractionValidator.ErrorCategory.UNSAFE,
                List.of(invalidVectorIssue),
                List.of(invalidVectorIssue.message()),
                List.of()
            );
        IntentExtractionValidator.ValidationResult actionMismatchValidation =
            new IntentExtractionValidator.ValidationResult(
                false,
                IntentExtractionValidator.ErrorCategory.UNSAFE,
                List.of(actionMismatchIssue),
                List.of(actionMismatchIssue.message()),
                List.of()
            );

        when(compound.attemptExtract(
            any(IntentExtractionInput.class),
            any(OrchestrationContext.class)
        )).thenReturn(invalidVectorAttempt, actionMismatchAttempt);
        when(postProcessor.postProcess(any(MultiIntentResponse.class), anyString()))
            .thenReturn(invalidVectorResponse, actionMismatchResponse);
        when(validator.validate(any(MultiIntentResponse.class), anyString()))
            .thenReturn(invalidVectorValidation, actionMismatchValidation);

        ProgressiveIntentExtractionEngine engine =
            new ProgressiveIntentExtractionEngine(
                properties,
                compound,
                repair,
                completion,
                multiStep,
                postProcessor,
                validator
            );
        IntentExtractionInput input = new IntentExtractionInput("q", "q", List.of());
        OrchestrationContext context = OrchestrationContext.forUser("user");

        ProgressiveIntentExtractionEngine.ExtractionOutput invalidVector =
            engine.extract(input, context, SemanticRecoveryPolicy.strictDefaults());
        ProgressiveIntentExtractionEngine.ExtractionOutput actionMismatch =
            engine.extract(input, context, SemanticRecoveryPolicy.strictDefaults());

        assertThat(invalidVector.response()).isSameAs(invalidVectorResponse);
        assertThat(semanticDecision(invalidVector))
            .containsEntry("decision", "VECTOR_SPACE_SELECTION")
            .containsEntry("recoveryPolicy", "LLM_REPAIR_THEN_FAIL")
            .containsEntry("effectiveValue", "DEFER_TO_VECTOR_SPACE_POLICY")
            .containsEntry("outcome", "DEFERRED_TO_GOVERNED_PIPELINE_STEP");
        assertThat(actionMismatch.response()).isSameAs(actionMismatchResponse);
        assertThat(semanticDecision(actionMismatch))
            .containsEntry("decision", "ACTION_EVIDENCE_DOMAIN")
            .containsEntry("recoveryPolicy", "DENY_AND_LLM_REPLAN")
            .containsEntry("effectiveValue", "DEFER_TO_ACTION_DOMAIN_POLICY")
            .containsEntry("outcome", "DEFERRED_TO_GOVERNED_PIPELINE_STEP");
    }

    @Test
    void reportsProviderFailureWhenTheBoundedLadderCannotCallTheLlm() {
        ProgressiveIntentExtractionProperties properties =
            new ProgressiveIntentExtractionProperties();
        properties.setForceMode("compound");
        properties.setMaxTotalLlmCalls(1);

        CompoundIntentExtractionStrategy compound =
            mock(CompoundIntentExtractionStrategy.class);
        RepairIntentExtractionStrategy repair =
            mock(RepairIntentExtractionStrategy.class);
        CompletionIntentExtractionStrategy completion =
            mock(CompletionIntentExtractionStrategy.class);
        MultiStepIntentExtractionStrategy multiStep =
            mock(MultiStepIntentExtractionStrategy.class);
        IntentExtractionPostProcessor postProcessor =
            mock(IntentExtractionPostProcessor.class);
        IntentExtractionValidator validator =
            mock(IntentExtractionValidator.class);

        when(compound.attemptExtract(
            any(IntentExtractionInput.class),
            any(OrchestrationContext.class)
        )).thenReturn(
            ExtractionAttempt.builder()
                .success(false)
                .strategyName("compound")
                .llmCalls(1)
                .errorMessage("provider call failed")
                .exception(new AIServiceException("provider call failed"))
                .build()
        );

        ProgressiveIntentExtractionEngine engine =
            new ProgressiveIntentExtractionEngine(
                properties,
                compound,
                repair,
                completion,
                multiStep,
                postProcessor,
                validator
            );

        ProgressiveIntentExtractionEngine.ExtractionOutput output =
            engine.extract(
                new IntentExtractionInput("q", "q", List.of()),
                OrchestrationContext.forUser("user")
            );

        assertThat(output.response()).isNull();
        assertThat(output.failure()).isNotNull();
        assertThat(output.failure().reason())
            .isEqualTo("INTENT_PROVIDER_FAILED");
        assertThat(output.failure().publicMessage())
            .doesNotContain("provider call failed");
        assertThat(output.failure().retryable()).isTrue();
        assertThat(output.diagnostics().toString())
            .contains("AI provider request failed")
            .doesNotContain("provider call failed");
        assertThat(semanticDecision(output))
            .containsEntry("originalSource", "MODEL")
            .containsEntry("effectiveSource", "NONE")
            .containsEntry("effectiveValue", "FAILURE")
            .containsEntry("outcome", "FAILED");
    }

    @Test
    void shouldReturnCompoundFastPathWhenSuccessful() {
        ProgressiveIntentExtractionProperties properties = new ProgressiveIntentExtractionProperties();
        properties.setRepairEnabled(true);
        properties.setRepairMaxAttempts(1);
        properties.setCompletionEnabled(true);
        properties.setCompletionMaxAttempts(1);
        properties.setMultiStepEnabled(true);
        properties.setMaxTotalLlmCalls(5);

        CompoundIntentExtractionStrategy compound = mock(CompoundIntentExtractionStrategy.class);
        RepairIntentExtractionStrategy repair = mock(RepairIntentExtractionStrategy.class);
        CompletionIntentExtractionStrategy completion = mock(CompletionIntentExtractionStrategy.class);
        MultiStepIntentExtractionStrategy multiStep = mock(MultiStepIntentExtractionStrategy.class);
        IntentExtractionPostProcessor postProcessor = mock(IntentExtractionPostProcessor.class);
        IntentExtractionValidator validator = mock(IntentExtractionValidator.class);

        MultiIntentResponse response = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder().type(IntentType.INFORMATION).intent("refund_policy").build()))
            .build();

        ExtractionAttempt compoundAttempt = ExtractionAttempt.builder()
            .success(true)
            .response(response)
            .validationResult(new IntentExtractionValidator.ValidationResult(true, IntentExtractionValidator.ErrorCategory.NONE, List.of(), List.of()))
            .strategyName("compound")
            .llmCalls(1)
            .processingTimeMs(180L)
            .providerProcessingTimeMs(140L)
            .model("gpt-5.4-nano")
            .build();

        when(compound.attemptExtract(any(IntentExtractionInput.class), any(OrchestrationContext.class))).thenReturn(compoundAttempt);
        when(postProcessor.postProcess(any(MultiIntentResponse.class), anyString())).thenReturn(response);
        when(validator.validate(any(MultiIntentResponse.class), anyString()))
            .thenReturn(new IntentExtractionValidator.ValidationResult(true, IntentExtractionValidator.ErrorCategory.NONE, List.of(), List.of()));

        ProgressiveIntentExtractionEngine engine = new ProgressiveIntentExtractionEngine(
            properties,
            compound,
            repair,
            completion,
            multiStep,
            postProcessor,
            validator
        );

        ProgressiveIntentExtractionEngine.ExtractionOutput output =
            engine.extract(new IntentExtractionInput("q", "q", List.of()), OrchestrationContext.forUser("user"));

        assertThat(output).isNotNull();
        assertThat(output.response()).isNotNull();
        assertThat(output.response().hasIntents()).isTrue();
        assertThat(output.diagnostics()).containsEntry("extractionPath", "compound");
        assertThat(output.diagnostics()).containsEntry("llmCalls", 1);
        assertThat(output.diagnostics()).containsEntry("providerProcessingTimeMs", 140L);
        assertThat(output.diagnostics()).containsEntry("model", "gpt-5.4-nano");
        assertThat(semanticDecision(output))
            .containsEntry("originalSource", "MODEL")
            .containsEntry("validationStatus", "VALID")
            .containsEntry("recoveryAttempted", false)
            .containsEntry("effectiveSource", "MODEL")
            .containsEntry("outcome", "VALIDATED");

        verify(repair, never()).attemptRepair(any(IntentExtractionInput.class), any(), any());
        verify(completion, never()).attemptComplete(any(IntentExtractionInput.class), any(), any(), any());
        verify(multiStep, never()).attemptExtract(any(IntentExtractionInput.class), any(), any(), any());
    }

    @Test
    void shouldAttemptRepairOnStructuralFailureAndReturnWhenRepairSucceeds() {
        ProgressiveIntentExtractionProperties properties = new ProgressiveIntentExtractionProperties();
        properties.setRepairEnabled(true);
        properties.setRepairMaxAttempts(1);
        properties.setCompletionEnabled(true);
        properties.setCompletionMaxAttempts(1);
        properties.setMultiStepEnabled(true);
        properties.setMaxTotalLlmCalls(5);

        CompoundIntentExtractionStrategy compound = mock(CompoundIntentExtractionStrategy.class);
        RepairIntentExtractionStrategy repair = mock(RepairIntentExtractionStrategy.class);
        CompletionIntentExtractionStrategy completion = mock(CompletionIntentExtractionStrategy.class);
        MultiStepIntentExtractionStrategy multiStep = mock(MultiStepIntentExtractionStrategy.class);
        IntentExtractionPostProcessor postProcessor = mock(IntentExtractionPostProcessor.class);
        IntentExtractionValidator validator = mock(IntentExtractionValidator.class);

        ExtractionAttempt compoundAttempt = ExtractionAttempt.builder()
            .success(false)
            .validationResult(new IntentExtractionValidator.ValidationResult(
                false,
                IntentExtractionValidator.ErrorCategory.STRUCTURAL,
                List.of("parse failed"),
                List.of()
            ))
            .strategyName("compound")
            .llmCalls(1)
            .processingTimeMs(170L)
            .providerProcessingTimeMs(120L)
            .model("gpt-5.4-nano")
            .build();

        MultiIntentResponse repaired = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder().type(IntentType.OUT_OF_SCOPE).intent("out_of_scope").build()))
            .build();

        ExtractionAttempt repairAttempt = ExtractionAttempt.builder()
            .success(true)
            .response(repaired)
            .validationResult(new IntentExtractionValidator.ValidationResult(true, IntentExtractionValidator.ErrorCategory.NONE, List.of(), List.of()))
            .strategyName("repair")
            .llmCalls(1)
            .processingTimeMs(90L)
            .providerProcessingTimeMs(70L)
            .model("gpt-5.4-nano")
            .build();

        when(compound.attemptExtract(any(IntentExtractionInput.class), any(OrchestrationContext.class))).thenReturn(compoundAttempt);
        when(repair.attemptRepair(any(IntentExtractionInput.class), any(OrchestrationContext.class), any(ExtractionAttempt.class))).thenReturn(repairAttempt);
        when(postProcessor.postProcess(any(MultiIntentResponse.class), anyString())).thenReturn(repaired);
        when(validator.validate(any(MultiIntentResponse.class), anyString()))
            .thenReturn(new IntentExtractionValidator.ValidationResult(true, IntentExtractionValidator.ErrorCategory.NONE, List.of(), List.of()));

        ProgressiveIntentExtractionEngine engine = new ProgressiveIntentExtractionEngine(
            properties,
            compound,
            repair,
            completion,
            multiStep,
            postProcessor,
            validator
        );

        ProgressiveIntentExtractionEngine.ExtractionOutput output =
            engine.extract(new IntentExtractionInput("q", "q", List.of()), OrchestrationContext.forUser("user"));

        assertThat(output.diagnostics()).containsEntry("extractionPath", "repair");
        assertThat(output.diagnostics()).containsEntry("llmCalls", 2);
        assertThat(output.diagnostics()).containsEntry("providerProcessingTimeMs", 190L);
        assertThat(semanticDecision(output))
            .containsEntry("recoveryAttempted", true)
            .containsEntry("effectiveSource", "MODEL_REPAIR")
            .containsEntry("modelCalls", 2);
        verify(completion, never()).attemptComplete(any(IntentExtractionInput.class), any(), any(), any());
        verify(multiStep, never()).attemptExtract(any(IntentExtractionInput.class), any(), any(), any());
    }

    @Test
    void shouldAttemptMultiStepAfterRepairFailure() {
        ProgressiveIntentExtractionProperties properties = new ProgressiveIntentExtractionProperties();
        properties.setRepairEnabled(true);
        properties.setRepairMaxAttempts(1);
        properties.setCompletionEnabled(true);
        properties.setCompletionMaxAttempts(1);
        properties.setMultiStepEnabled(true);
        properties.setMaxTotalLlmCalls(5);

        CompoundIntentExtractionStrategy compound = mock(CompoundIntentExtractionStrategy.class);
        RepairIntentExtractionStrategy repair = mock(RepairIntentExtractionStrategy.class);
        CompletionIntentExtractionStrategy completion = mock(CompletionIntentExtractionStrategy.class);
        MultiStepIntentExtractionStrategy multiStep = mock(MultiStepIntentExtractionStrategy.class);
        IntentExtractionPostProcessor postProcessor = mock(IntentExtractionPostProcessor.class);
        IntentExtractionValidator validator = mock(IntentExtractionValidator.class);

        ExtractionAttempt compoundAttempt = ExtractionAttempt.builder()
            .success(false)
            .validationResult(new IntentExtractionValidator.ValidationResult(
                false,
                IntentExtractionValidator.ErrorCategory.STRUCTURAL,
                List.of("parse failed"),
                List.of()
            ))
            .strategyName("compound")
            .llmCalls(1)
            .processingTimeMs(100L)
            .providerProcessingTimeMs(80L)
            .model("gpt-5.4-nano")
            .build();

        ExtractionAttempt repairAttempt = ExtractionAttempt.builder()
            .success(false)
            .validationResult(new IntentExtractionValidator.ValidationResult(
                false,
                IntentExtractionValidator.ErrorCategory.STRUCTURAL,
                List.of("repair failed"),
                List.of()
            ))
            .strategyName("repair")
            .llmCalls(1)
            .processingTimeMs(110L)
            .providerProcessingTimeMs(85L)
            .model("gpt-5.4-nano")
            .build();

        MultiIntentResponse multiStepResponse = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder().type(IntentType.INFORMATION).intent("refund_policy").build()))
            .build();

        ExtractionAttempt multiStepAttempt = ExtractionAttempt.builder()
            .success(true)
            .response(multiStepResponse)
            .validationResult(new IntentExtractionValidator.ValidationResult(true, IntentExtractionValidator.ErrorCategory.NONE, List.of(), List.of()))
            .strategyName("multi_step")
            .llmCalls(2)
            .processingTimeMs(210L)
            .providerProcessingTimeMs(160L)
            .model("gpt-5.4-nano")
            .build();

        when(compound.attemptExtract(any(IntentExtractionInput.class), any(OrchestrationContext.class))).thenReturn(compoundAttempt);
        when(repair.attemptRepair(any(IntentExtractionInput.class), any(OrchestrationContext.class), any(ExtractionAttempt.class))).thenReturn(repairAttempt);
        when(multiStep.attemptExtract(
            any(IntentExtractionInput.class),
            any(OrchestrationContext.class),
            nullable(ExtractionAttempt.class),
            any(SemanticRecoveryPolicy.class)
        )).thenReturn(multiStepAttempt);
        when(postProcessor.postProcess(any(MultiIntentResponse.class), anyString())).thenReturn(multiStepResponse);
        when(validator.validate(any(MultiIntentResponse.class), anyString()))
            .thenReturn(new IntentExtractionValidator.ValidationResult(true, IntentExtractionValidator.ErrorCategory.NONE, List.of(), List.of()));

        ProgressiveIntentExtractionEngine engine = new ProgressiveIntentExtractionEngine(
            properties,
            compound,
            repair,
            completion,
            multiStep,
            postProcessor,
            validator
        );

        ProgressiveIntentExtractionEngine.ExtractionOutput output =
            engine.extract(new IntentExtractionInput("q", "q", List.of()), OrchestrationContext.forUser("user"));

        assertThat(output.diagnostics()).containsEntry("extractionPath", "multi_step");
        assertThat(output.diagnostics()).containsEntry("llmCalls", 4);
        assertThat(output.diagnostics()).containsEntry("providerProcessingTimeMs", 325L);
        verify(multiStep).attemptExtract(
            any(IntentExtractionInput.class),
            any(OrchestrationContext.class),
            nullable(ExtractionAttempt.class),
            any(SemanticRecoveryPolicy.class)
        );
    }

    @Test
    void shouldAttemptCompletionWhenCompoundIsContractIncomplete() {
        ProgressiveIntentExtractionProperties properties = new ProgressiveIntentExtractionProperties();
        properties.setRepairEnabled(true);
        properties.setRepairMaxAttempts(1);
        properties.setCompletionEnabled(true);
        properties.setCompletionMaxAttempts(1);
        properties.setMultiStepEnabled(true);
        properties.setMaxTotalLlmCalls(5);

        CompoundIntentExtractionStrategy compound = mock(CompoundIntentExtractionStrategy.class);
        RepairIntentExtractionStrategy repair = mock(RepairIntentExtractionStrategy.class);
        CompletionIntentExtractionStrategy completion = mock(CompletionIntentExtractionStrategy.class);
        MultiStepIntentExtractionStrategy multiStep = mock(MultiStepIntentExtractionStrategy.class);
        IntentExtractionPostProcessor postProcessor = mock(IntentExtractionPostProcessor.class);
        IntentExtractionValidator validator = mock(IntentExtractionValidator.class);

        MultiIntentResponse incomplete = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(IntentType.ACTION)
                .action("relationship_query")
                .actionParams(java.util.Map.of())
                .build()))
            .build();

        ExtractionAttempt rawCompound = ExtractionAttempt.builder()
            .success(true)
            .response(incomplete)
            .strategyName("compound")
            .llmCalls(1)
            .processingTimeMs(145L)
            .providerProcessingTimeMs(115L)
            .model("gpt-5.4-nano")
            .build();

        MultiIntentResponse completedResponse = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(IntentType.ACTION)
                .intent("relationship_query")
                .action("relationship_query")
                .actionParams(java.util.Map.of("query", "q"))
                .requiresRetrieval(false)
                .requiresGeneration(false)
                .requiresTargetResolution(false)
                .build()))
            .build();

        ExtractionAttempt rawCompletion = ExtractionAttempt.builder()
            .success(true)
            .response(completedResponse)
            .strategyName("completion")
            .llmCalls(1)
            .processingTimeMs(95L)
            .providerProcessingTimeMs(74L)
            .model("gpt-5.4-nano")
            .build();

        when(compound.attemptExtract(any(IntentExtractionInput.class), any(OrchestrationContext.class))).thenReturn(rawCompound);
        when(completion.attemptComplete(
            any(IntentExtractionInput.class),
            any(OrchestrationContext.class),
            any(ExtractionAttempt.class),
            any(SemanticRecoveryPolicy.class)
        )).thenReturn(rawCompletion);
        when(postProcessor.postProcess(any(MultiIntentResponse.class), anyString()))
            .thenReturn(incomplete)
            .thenReturn(completedResponse);

        IntentExtractionValidator.ValidationIssue incompleteIssue =
            new IntentExtractionValidator.ValidationIssue(
                IntentExtractionValidator.IssueCode.INTENT_MISSING_RETRIEVAL_DECISION,
                IntentExtractionValidator.Severity.ERROR,
                0,
                "requiresRetrieval",
                "Missing requiresRetrieval semantic decision"
            );
        IntentExtractionValidator.ValidationResult incompleteValidation =
            new IntentExtractionValidator.ValidationResult(
                false,
                IntentExtractionValidator.ErrorCategory.INCOMPLETE,
                List.of(incompleteIssue),
                List.of(incompleteIssue.message()),
                List.of()
            );
        when(validator.validate(any(MultiIntentResponse.class), anyString()))
            .thenReturn(incompleteValidation)
            .thenReturn(new IntentExtractionValidator.ValidationResult(true, IntentExtractionValidator.ErrorCategory.NONE, List.of(), List.of()));

        ProgressiveIntentExtractionEngine engine = new ProgressiveIntentExtractionEngine(
            properties,
            compound,
            repair,
            completion,
            multiStep,
            postProcessor,
            validator
        );

        ProgressiveIntentExtractionEngine.ExtractionOutput output =
            engine.extract(new IntentExtractionInput("q", "q", List.of()), OrchestrationContext.forUser("user"));

        assertThat(output.diagnostics()).containsEntry("extractionPath", "completion");
        assertThat(output.diagnostics()).containsEntry("llmCalls", 2);
        assertThat(output.diagnostics()).containsEntry("providerProcessingTimeMs", 189L);
        assertThat(semanticDecision(output))
            .containsEntry("recoveryAttempted", true)
            .containsEntry("effectiveSource", "MODEL_COMPLETION")
            .containsEntry("modelCalls", 2);
        verify(repair, never()).attemptRepair(any(IntentExtractionInput.class), any(), any());
        verify(multiStep, never()).attemptExtract(any(IntentExtractionInput.class), any(), any(), any());
        verify(completion).attemptComplete(any(IntentExtractionInput.class), any(), any(), any());
    }

    @Test
    void rejectsUnknownActionReplanForOnlyTheFailedObligationUnderStrictPolicy() {
        MultiIntentResponse candidate = validActionAndInformationCandidate();

        RecoveryRun run = runUnknownActionRecovery(
            candidate,
            SemanticRecoveryPolicy.strictDefaults()
        );

        assertThat(run.output().response()).isNull();
        assertThat(run.output().failure()).isNotNull();
        assertThat(run.output().failure().issueCodes())
            .contains(IntentExtractionValidator.IssueCode.SEMANTIC_RECOVERY_NOT_ALLOWED);
        assertThat(recoveryTransitions(run.output()))
            .singleElement()
            .satisfies(transition -> {
                assertThat(transition)
                    .containsEntry("obligationId", "obligation-policy")
                    .containsEntry("decision", "ACTION_UNREGISTERED")
                    .containsEntry("outcome", "REJECTED")
                    .containsEntry("policyOrReason", "LLM_REPAIR_THEN_FAIL");
            });
        verify(run.completion(), never()).attemptComplete(any(), any(), any(), any());
    }

    @Test
    void allowsUnknownActionReplanForOnlyTheFailedObligationWhenPolicyPermitsIt() {
        MultiIntentResponse candidate = validActionAndInformationCandidate();
        SemanticRecoveryPolicy policy = new SemanticRecoveryPolicy(
            null,
            null,
            null,
            null,
            OrchestrationProperties.UnknownActionRecovery.LLM_REPLAN_TO_INFORMATION,
            null,
            null
        );

        RecoveryRun run = runUnknownActionRecovery(candidate, policy);

        assertThat(run.output().failure()).isNull();
        assertThat(run.output().response()).isSameAs(candidate);
        assertThat(run.output().diagnostics())
            .containsEntry("extractionPath", "multi_step");
        assertThat(recoveryTransitions(run.output()))
            .singleElement()
            .satisfies(transition -> {
                assertThat(transition)
                    .containsEntry("obligationId", "obligation-policy")
                    .containsEntry("decision", "ACTION_UNREGISTERED")
                    .containsEntry("outcome", "MODEL_REPLAN_TO_INFORMATION")
                    .containsEntry("policyOrReason", "LLM_REPLAN_TO_INFORMATION");
            });
        verify(run.multiStep()).attemptExtract(any(), any(), any(), any());
    }

    @Test
    void preservesAnAlreadyValidSiblingWhenModelRecoveryChangesIt() {
        MultiIntentResponse candidate = MultiIntentResponse.builder()
            .intents(List.of(
                informationIntent("obligation-stock", "dealer-vehicle"),
                informationIntent("obligation-policy", "document")
            ))
            .build();
        SemanticRecoveryPolicy policy = new SemanticRecoveryPolicy(
            null,
            null,
            null,
            null,
            OrchestrationProperties.UnknownActionRecovery.LLM_REPLAN_TO_INFORMATION,
            null,
            null
        );

        RecoveryRun run = runUnknownActionRecovery(candidate, policy);

        assertThat(run.output().failure()).isNull();
        assertThat(run.output().response().getIntents())
            .satisfiesExactly(
                intent -> {
                    assertThat(intent.getType()).isEqualTo(IntentType.ACTION);
                    assertThat(intent.getAction()).isEqualTo("search_inventory");
                    assertThat(intent.getObligationId()).isEqualTo("obligation-stock");
                },
                intent -> {
                    assertThat(intent.getType()).isEqualTo(IntentType.INFORMATION);
                    assertThat(intent.getVectorSpace()).isEqualTo("document");
                }
            );
        assertThat(recoveryTransitions(run.output()))
            .anySatisfy(transition -> assertThat(transition)
                .containsEntry("obligationId", "obligation-stock")
                .containsEntry("decision", "VALID_SIBLING_PRESERVATION")
                .containsEntry("modelProposedType", "INFORMATION")
                .containsEntry("effectiveType", "ACTION")
                .containsEntry("effectiveActionId", "search_inventory")
                .containsEntry("outcome", "SERVER_PRESERVED_VALID_SIBLING"));
    }

    @Test
    void preservesValidSiblingSemanticsWhileAcceptingRecoveredObligationIds() {
        Intent originalStock = actionIntent(null, "search_inventory");
        originalStock.setActionParams(Map.of("fuelType", "Electric", "maxPrice", 40_000));
        MultiIntentResponse rejected = MultiIntentResponse.builder()
            .intents(List.of(
                originalStock,
                actionIntent(null, "invented_policy_action")
            ))
            .build();
        IntentExtractionValidator.ValidationIssue stockObligationId =
            new IntentExtractionValidator.ValidationIssue(
                IntentExtractionValidator.IssueCode.INTENT_MISSING_OBLIGATION_ID,
                IntentExtractionValidator.Severity.ERROR,
                0,
                "obligationId",
                "Compound intent is missing an obligationId"
            );
        IntentExtractionValidator.ValidationIssue policyObligationId =
            new IntentExtractionValidator.ValidationIssue(
                IntentExtractionValidator.IssueCode.INTENT_MISSING_OBLIGATION_ID,
                IntentExtractionValidator.Severity.ERROR,
                1,
                "obligationId",
                "Compound intent is missing an obligationId"
            );
        IntentExtractionValidator.ValidationIssue unknownAction =
            new IntentExtractionValidator.ValidationIssue(
                IntentExtractionValidator.IssueCode.ACTION_UNREGISTERED,
                IntentExtractionValidator.Severity.ERROR,
                1,
                "action",
                "No handler registered for model-selected action"
            );
        IntentExtractionValidator.ValidationResult rejectedValidation =
            new IntentExtractionValidator.ValidationResult(
                false,
                IntentExtractionValidator.ErrorCategory.UNSAFE,
                List.of(stockObligationId, policyObligationId, unknownAction),
                List.of(
                    stockObligationId.message(),
                    policyObligationId.message(),
                    unknownAction.message()
                ),
                List.of()
            );
        MultiIntentResponse candidate = MultiIntentResponse.builder()
            .intents(List.of(
                informationIntent("obligation-stock", "dealer-vehicle"),
                informationIntent("obligation-policy", "document")
            ))
            .build();
        SemanticRecoveryPolicy policy = new SemanticRecoveryPolicy(
            null,
            null,
            null,
            null,
            OrchestrationProperties.UnknownActionRecovery.LLM_REPLAN_TO_INFORMATION,
            null,
            null
        );

        RecoveryRun run = runUnknownActionRecovery(
            rejected,
            rejectedValidation,
            candidate,
            policy
        );

        assertThat(run.output().failure()).isNull();
        assertThat(run.output().response().getIntents())
            .satisfiesExactly(
                intent -> {
                    assertThat(intent.getType()).isEqualTo(IntentType.ACTION);
                    assertThat(intent.getAction()).isEqualTo("search_inventory");
                    assertThat(intent.getActionParams()).containsExactlyInAnyOrderEntriesOf(
                        Map.of("fuelType", "Electric", "maxPrice", 40_000)
                    );
                    assertThat(intent.getObligationId()).isEqualTo("obligation-stock");
                },
                intent -> {
                    assertThat(intent.getType()).isEqualTo(IntentType.INFORMATION);
                    assertThat(intent.getVectorSpace()).isEqualTo("document");
                    assertThat(intent.getObligationId()).isEqualTo("obligation-policy");
                }
            );
        assertThat(recoveryTransitions(run.output()))
            .anySatisfy(transition -> assertThat(transition)
                .containsEntry("decision", "VALID_SIBLING_PRESERVATION")
                .containsEntry("outcome", "SERVER_PRESERVED_VALID_SIBLING"));
    }

    @Test
    void allowsActionDomainMismatchReplanOnlyWhenPolicyNamesModelReplan() {
        MultiIntentResponse candidate = MultiIntentResponse.builder()
            .intents(List.of(
                actionIntent("obligation-stock", "search_inventory"),
                informationIntent("obligation-policy", "document")
            ))
            .build();

        RecoveryRun run = runActionDomainRecovery(
            candidate,
            SemanticRecoveryPolicy.strictDefaults()
        );

        assertThat(run.output().failure()).isNull();
        assertThat(run.output().response()).isSameAs(candidate);
        assertThat(recoveryTransitions(run.output()))
            .singleElement()
            .satisfies(transition -> assertThat(transition)
                .containsEntry("obligationId", "obligation-policy")
                .containsEntry(
                    "decision",
                    "ACTION_GROUNDING_VECTOR_SPACE_MISMATCH"
                )
                .containsEntry("outcome", "MODEL_REPLAN_TO_INFORMATION")
                .containsEntry("policyOrReason", "DENY_AND_LLM_REPLAN"));
        verify(run.multiStep()).attemptExtract(any(), any(), any(), any());
    }

    @Test
    void doesNotInvokeModelReplanWhenActionDomainPolicyRequiresFailure() {
        SemanticRecoveryPolicy policy = new SemanticRecoveryPolicy(
            null,
            null,
            null,
            null,
            null,
            OrchestrationProperties.ActionDomainMismatchRecovery.DENY_AND_FAIL,
            null
        );

        RecoveryRun run = runActionDomainRecovery(
            validActionAndInformationCandidate(),
            policy
        );

        assertThat(run.output().failure()).isNull();
        assertThat(run.output().diagnostics())
            .containsEntry("extractionPath", "policy_recovery");
        assertThat(semanticDecision(run.output()))
            .containsEntry("decision", "ACTION_EVIDENCE_DOMAIN")
            .containsEntry("recoveryPolicy", "DENY_AND_FAIL")
            .containsEntry("effectiveValue", "DEFER_TO_ACTION_DOMAIN_POLICY");
        verify(run.multiStep(), never()).attemptExtract(any(), any(), any(), any());
    }

    private RecoveryRun runUnknownActionRecovery(
        MultiIntentResponse candidate,
        SemanticRecoveryPolicy policy
    ) {
        MultiIntentResponse rejected = MultiIntentResponse.builder()
            .intents(List.of(
                actionIntent("obligation-stock", "search_inventory"),
                actionIntent("obligation-policy", "invented_policy_action")
            ))
            .build();
        IntentExtractionValidator.ValidationIssue unknownAction =
            new IntentExtractionValidator.ValidationIssue(
                IntentExtractionValidator.IssueCode.ACTION_UNREGISTERED,
                IntentExtractionValidator.Severity.ERROR,
                1,
                "action",
                "No handler registered for model-selected action"
            );
        IntentExtractionValidator.ValidationResult rejectedValidation =
            new IntentExtractionValidator.ValidationResult(
                false,
                IntentExtractionValidator.ErrorCategory.UNSAFE,
                List.of(unknownAction),
                List.of(unknownAction.message()),
                List.of()
            );
        return runUnknownActionRecovery(
            rejected,
            rejectedValidation,
            candidate,
            policy
        );
    }

    private RecoveryRun runUnknownActionRecovery(
        MultiIntentResponse rejected,
        IntentExtractionValidator.ValidationResult rejectedValidation,
        MultiIntentResponse candidate,
        SemanticRecoveryPolicy policy
    ) {
        ProgressiveIntentExtractionProperties properties =
            new ProgressiveIntentExtractionProperties();
        properties.setRepairEnabled(false);
        properties.setCompletionEnabled(true);
        properties.setMultiStepEnabled(true);
        properties.setMaxTotalLlmCalls(4);

        CompoundIntentExtractionStrategy compound =
            mock(CompoundIntentExtractionStrategy.class);
        RepairIntentExtractionStrategy repair =
            mock(RepairIntentExtractionStrategy.class);
        CompletionIntentExtractionStrategy completion =
            mock(CompletionIntentExtractionStrategy.class);
        MultiStepIntentExtractionStrategy multiStep =
            mock(MultiStepIntentExtractionStrategy.class);
        IntentExtractionPostProcessor postProcessor =
            mock(IntentExtractionPostProcessor.class);
        IntentExtractionValidator validator =
            mock(IntentExtractionValidator.class);

        IntentExtractionValidator.ValidationResult valid =
            new IntentExtractionValidator.ValidationResult(
                true,
                IntentExtractionValidator.ErrorCategory.NONE,
                List.of(),
                List.of(),
                List.of()
            );

        when(compound.attemptExtract(any(), any())).thenReturn(
            ExtractionAttempt.builder()
                .success(false)
                .response(rejected)
                .validationResult(rejectedValidation)
                .strategyName("compound")
                .llmCalls(1)
                .build()
        );
        when(multiStep.attemptExtract(any(), any(), any(), any())).thenReturn(
            ExtractionAttempt.builder()
                .success(true)
                .response(candidate)
                .validationResult(valid)
                .strategyName("multi_step")
                .llmCalls(1)
                .build()
        );
        when(postProcessor.postProcess(any(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(validator.validate(any(), anyString()))
            .thenReturn(rejectedValidation, valid, valid);

        ProgressiveIntentExtractionEngine engine =
            new ProgressiveIntentExtractionEngine(
                properties,
                compound,
                repair,
                completion,
                multiStep,
                postProcessor,
                validator
            );
        ProgressiveIntentExtractionEngine.ExtractionOutput output = engine.extract(
            new IntentExtractionInput("q", "q", List.of()),
            OrchestrationContext.forUser("user"),
            policy
        );
        return new RecoveryRun(output, completion, multiStep);
    }

    private RecoveryRun runActionDomainRecovery(
        MultiIntentResponse candidate,
        SemanticRecoveryPolicy policy
    ) {
        ProgressiveIntentExtractionProperties properties =
            new ProgressiveIntentExtractionProperties();
        properties.setRepairEnabled(false);
        properties.setCompletionEnabled(true);
        properties.setMultiStepEnabled(true);
        properties.setMaxTotalLlmCalls(4);

        CompoundIntentExtractionStrategy compound =
            mock(CompoundIntentExtractionStrategy.class);
        RepairIntentExtractionStrategy repair =
            mock(RepairIntentExtractionStrategy.class);
        CompletionIntentExtractionStrategy completion =
            mock(CompletionIntentExtractionStrategy.class);
        MultiStepIntentExtractionStrategy multiStep =
            mock(MultiStepIntentExtractionStrategy.class);
        IntentExtractionPostProcessor postProcessor =
            mock(IntentExtractionPostProcessor.class);
        IntentExtractionValidator validator =
            mock(IntentExtractionValidator.class);

        MultiIntentResponse rejected = MultiIntentResponse.builder()
            .intents(List.of(
                actionIntent("obligation-stock", "search_inventory"),
                Intent.builder()
                    .type(IntentType.ACTION)
                    .intent("read_policy")
                    .action("read_policy")
                    .obligationId("obligation-policy")
                    .userGoal("structured policy goal")
                    .vectorSpace("dealer-vehicle")
                    .requiresRetrieval(true)
                    .requiresGeneration(true)
                    .requiresTargetResolution(false)
                    .needsAdvancedRAG(false)
                    .build()
            ))
            .build();
        IntentExtractionValidator.ValidationIssue mismatch =
            new IntentExtractionValidator.ValidationIssue(
                IntentExtractionValidator.IssueCode.ACTION_GROUNDING_VECTOR_SPACE_MISMATCH,
                IntentExtractionValidator.Severity.ERROR,
                1,
                "vectorSpace",
                "Selected vector space is outside the action evidence domain"
            );
        IntentExtractionValidator.ValidationResult rejectedValidation =
            new IntentExtractionValidator.ValidationResult(
                false,
                IntentExtractionValidator.ErrorCategory.UNSAFE,
                List.of(mismatch),
                List.of(mismatch.message()),
                List.of()
            );
        IntentExtractionValidator.ValidationResult valid =
            new IntentExtractionValidator.ValidationResult(
                true,
                IntentExtractionValidator.ErrorCategory.NONE,
                List.of(),
                List.of(),
                List.of()
            );

        when(compound.attemptExtract(any(), any())).thenReturn(
            ExtractionAttempt.builder()
                .success(false)
                .response(rejected)
                .validationResult(rejectedValidation)
                .strategyName("compound")
                .llmCalls(1)
                .build()
        );
        when(multiStep.attemptExtract(any(), any(), any(), any())).thenReturn(
            ExtractionAttempt.builder()
                .success(true)
                .response(candidate)
                .validationResult(valid)
                .strategyName("multi_step")
                .llmCalls(1)
                .build()
        );
        when(postProcessor.postProcess(any(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(validator.validate(any(), anyString()))
            .thenReturn(rejectedValidation, valid);

        ProgressiveIntentExtractionEngine engine =
            new ProgressiveIntentExtractionEngine(
                properties,
                compound,
                repair,
                completion,
                multiStep,
                postProcessor,
                validator
            );
        ProgressiveIntentExtractionEngine.ExtractionOutput output = engine.extract(
            new IntentExtractionInput("q", "q", List.of()),
            OrchestrationContext.forUser("user"),
            policy
        );
        return new RecoveryRun(output, completion, multiStep);
    }

    private MultiIntentResponse validActionAndInformationCandidate() {
        return MultiIntentResponse.builder()
            .intents(List.of(
                actionIntent("obligation-stock", "search_inventory"),
                informationIntent("obligation-policy", "document")
            ))
            .build();
    }

    private Intent actionIntent(String obligationId, String actionId) {
        return Intent.builder()
            .type(IntentType.ACTION)
            .intent(actionId)
            .action(actionId)
            .obligationId(obligationId)
            .userGoal("structured action goal")
            .requiresRetrieval(false)
            .requiresGeneration(false)
            .requiresTargetResolution(false)
            .build();
    }

    private Intent informationIntent(String obligationId, String vectorSpace) {
        return Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("grounded_information")
            .obligationId(obligationId)
            .userGoal("structured information goal")
            .vectorSpace(vectorSpace)
            .requiresRetrieval(true)
            .requiresGeneration(true)
            .requiresTargetResolution(false)
            .needsAdvancedRAG(false)
            .build();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> recoveryTransitions(
        ProgressiveIntentExtractionEngine.ExtractionOutput output
    ) {
        List<Map<String, Object>> attempts =
            (List<Map<String, Object>>) output.diagnostics().get("attempts");
        return (List<Map<String, Object>>) attempts.getLast()
            .get("semanticRecoveryTransitions");
    }

    private record RecoveryRun(
        ProgressiveIntentExtractionEngine.ExtractionOutput output,
        CompletionIntentExtractionStrategy completion,
        MultiStepIntentExtractionStrategy multiStep
    ) {
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> semanticDecision(
        ProgressiveIntentExtractionEngine.ExtractionOutput output
    ) {
        return (Map<String, Object>) output.diagnostics().get("semanticDecision");
    }
}
