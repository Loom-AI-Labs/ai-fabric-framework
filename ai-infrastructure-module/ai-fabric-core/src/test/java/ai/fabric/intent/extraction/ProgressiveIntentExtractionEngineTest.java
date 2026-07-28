package ai.fabric.intent.extraction;

import ai.fabric.config.ProgressiveIntentExtractionProperties;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.exception.AIServiceException;
import ai.fabric.intent.IntentExtractionPostProcessor;
import ai.fabric.intent.IntentExtractionValidator;
import ai.fabric.intent.orchestration.OrchestrationContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProgressiveIntentExtractionEngineTest {

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

        assertThat(output.response().getMetadata())
            .containsEntry("fallback", true);
        assertThat(output.failure()).isNotNull();
        assertThat(output.failure().reason())
            .isEqualTo("INTENT_PROVIDER_FAILED");
        assertThat(output.failure().publicMessage())
            .doesNotContain("provider call failed");
        assertThat(output.failure().retryable()).isTrue();
        assertThat(output.diagnostics().toString())
            .contains("AI provider request failed")
            .doesNotContain("provider call failed");
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

        verify(repair, never()).attemptRepair(any(IntentExtractionInput.class), any(), any());
        verify(completion, never()).attemptComplete(any(IntentExtractionInput.class), any(), any());
        verify(multiStep, never()).attemptExtract(any(IntentExtractionInput.class), any());
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
        verify(completion, never()).attemptComplete(any(IntentExtractionInput.class), any(), any());
        verify(multiStep, never()).attemptExtract(any(IntentExtractionInput.class), any());
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
        when(multiStep.attemptExtract(any(IntentExtractionInput.class), any(OrchestrationContext.class))).thenReturn(multiStepAttempt);
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
        verify(multiStep).attemptExtract(any(IntentExtractionInput.class), any(OrchestrationContext.class));
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
            .intents(List.of(Intent.builder().type(IntentType.INFORMATION).intent("refund_policy").build()))
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
        when(completion.attemptComplete(any(IntentExtractionInput.class), any(OrchestrationContext.class), any(ExtractionAttempt.class))).thenReturn(rawCompletion);
        when(postProcessor.postProcess(any(MultiIntentResponse.class), anyString()))
            .thenReturn(incomplete)
            .thenReturn(completedResponse);

        IntentExtractionValidator.ValidationResult incompleteValidation = new IntentExtractionValidator.ValidationResult(
            false,
            IntentExtractionValidator.ErrorCategory.INCOMPLETE,
            List.of("missing params"),
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
        verify(repair, never()).attemptRepair(any(IntentExtractionInput.class), any(), any());
        verify(multiStep, never()).attemptExtract(any(IntentExtractionInput.class), any());
        verify(completion).attemptComplete(any(IntentExtractionInput.class), any(), any());
    }
}
