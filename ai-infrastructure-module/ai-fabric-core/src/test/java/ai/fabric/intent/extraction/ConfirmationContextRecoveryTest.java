package ai.fabric.intent.extraction;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.config.ProgressiveIntentExtractionProperties;
import ai.fabric.dto.AIChatMessage;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.IntentExtractionPostProcessor;
import ai.fabric.intent.IntentExtractionValidator;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.policy.SemanticRecoveryPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConfirmationContextRecoveryTest {
    private final CompoundIntentExtractionStrategy compound = mock(CompoundIntentExtractionStrategy.class);
    private final RepairIntentExtractionStrategy repair = mock(RepairIntentExtractionStrategy.class);
    private final CompletionIntentExtractionStrategy completion = mock(CompletionIntentExtractionStrategy.class);
    private final MultiStepIntentExtractionStrategy multi = mock(MultiStepIntentExtractionStrategy.class);
    private final ProgressiveIntentExtractionProperties settings = new ProgressiveIntentExtractionProperties();
    private final OrchestrationContext context = OrchestrationContext.forUser("owner");

    private IntentExtractionInput input(Boolean pending) {
        return new IntentExtractionInput("yes please do", "yes please do",
            List.of(AIChatMessage.assistant("I can turn the setup guide into a checklist.")),
            null, "Server-owned confirmation state", pending);
    }

    private MultiIntentResponse response(IntentType type) {
        return MultiIntentResponse.builder().intents(List.of(Intent.builder()
            .obligationId("offer-1").intent("continue_setup").type(type).confidence(0.9)
            .requiresRetrieval(false).requiresGeneration(type == IntentType.INFORMATION)
            .requiresTargetResolution(false).needsAdvancedRAG(false).build())).build();
    }

    private ExtractionAttempt attempt(MultiIntentResponse response, String strategy) {
        return ExtractionAttempt.builder().success(true).response(response).llmCalls(1).strategyName(strategy).build();
    }

    private ProgressiveIntentExtractionEngine engine() {
        settings.setMaxTotalLlmCalls(4);
        return new ProgressiveIntentExtractionEngine(settings, compound, repair, completion, multi,
            new IntentExtractionPostProcessor(null), new IntentExtractionValidator(null));
    }

    private SemanticRecoveryPolicy correctionPolicy() {
        return new SemanticRecoveryPolicy(null, null, null, null, null, null, null,
            OrchestrationProperties.ConfirmationWithoutPendingRecovery.LLM_REPLAN_THEN_FAIL);
    }

    @ParameterizedTest
    @EnumSource(value = IntentType.class, names = {"CONFIRMATION_POSITIVE", "CONFIRMATION_NEGATIVE"})
    void configuredRecoveryLetsTheModelResolveAnInformationalContinuation(IntentType wrong) {
        var original = response(wrong);
        var corrected = response(IntentType.INFORMATION);
        when(compound.attemptExtract(any(), any())).thenReturn(attempt(original, "compound"));
        when(multi.attemptExtract(any(), any(), any(), any())).thenReturn(attempt(corrected, "multi_step"));
        var input = input(false);
        var result = engine().extract(input, context, correctionPolicy());
        assertThat(result.failure()).isNull();
        assertThat(result.response().getIntents().getFirst().getType()).isEqualTo(IntentType.INFORMATION);
        assertThat(result.diagnostics().toString()).contains("CONFIRMATION_WITHOUT_PENDING_ACTION", "MODEL_CONFIRMATION_STATE_REPLAN");
        verify(multi).attemptExtract(eq(input), eq(context), any(), any());
        verifyNoInteractions(completion, repair);
    }

    @Test
    void failPolicyDoesNotCallAnotherModelOrSilentlyReplaceTheDecision() {
        when(compound.attemptExtract(any(), any())).thenReturn(attempt(response(IntentType.CONFIRMATION_POSITIVE), "compound"));
        var policy = SemanticRecoveryPolicy.strictDefaults();
        var result = engine().extract(input(false), context, policy);
        assertThat(result.response()).isNull();
        assertThat(result.failure().issueCodes()).contains(IntentExtractionValidator.IssueCode.CONFIRMATION_WITHOUT_PENDING_ACTION);
        verifyNoInteractions(multi, completion, repair);
    }

    @Test
    void anUncorrectedConfirmationFailsWithinTheExistingAttemptBudget() {
        when(compound.attemptExtract(any(), any())).thenReturn(attempt(response(IntentType.CONFIRMATION_POSITIVE), "compound"));
        when(multi.attemptExtract(any(), any(), any(), any())).thenReturn(attempt(response(IntentType.CONFIRMATION_POSITIVE), "multi_step"));
        var result = engine().extract(input(false), context, correctionPolicy());
        assertThat(result.response()).isNull();
        assertThat(result.failure().issueCodes()).contains(IntentExtractionValidator.IssueCode.CONFIRMATION_WITHOUT_PENDING_ACTION);
        verify(multi, times(1)).attemptExtract(any(), any(), any(), any());
    }

    @Test
    void realPendingStateAndUnavailableStateDoNotInventAValidationFailure() {
        for (Boolean pending : new Boolean[]{true, null}) {
            when(compound.attemptExtract(any(), any())).thenReturn(attempt(response(IntentType.CONFIRMATION_POSITIVE), "compound"));
            var result = engine().extract(input(pending), context);
            assertThat(result.failure()).isNull();
            assertThat(result.response().getIntents().getFirst().getType()).isEqualTo(IntentType.CONFIRMATION_POSITIVE);
        }
        verifyNoInteractions(multi, completion, repair);
    }

    @Test
    void vectorDeferralCannotHideAReplanThatDroppedAValidSibling() {
        var original = response(IntentType.CONFIRMATION_POSITIVE);
        var sibling = response(IntentType.INFORMATION).getIntents().getFirst();
        sibling.setObligationId("keep-this-sibling");
        original.setIntents(List.of(original.getIntents().getFirst(), sibling));
        var incomplete = response(IntentType.INFORMATION);
        incomplete.getIntents().getFirst().setRequiresRetrieval(true);
        when(compound.attemptExtract(any(), any())).thenReturn(attempt(original, "compound"));
        when(multi.attemptExtract(any(), any(), any(), any())).thenReturn(attempt(incomplete, "multi_step"));
        var policy = new SemanticRecoveryPolicy(null, null,
            OrchestrationProperties.MissingVectorSpaceRecovery.BOUNDED_ALLOWED_FAN_OUT,
            null, null, null, null, OrchestrationProperties.ConfirmationWithoutPendingRecovery.LLM_REPLAN_THEN_FAIL);
        var result = engine().extract(input(false), context, policy);
        assertThat(result.response()).isNull();
        assertThat(result.failure().issueCodes()).contains(IntentExtractionValidator.IssueCode.SEMANTIC_RECOVERY_NOT_ALLOWED);
    }

    @Test
    void completionCannotIntroduceAnUnconfiguredConfirmationCorrection() {
        var incomplete = response(IntentType.INFORMATION);
        incomplete.getIntents().getFirst().setRequiresGeneration(null);
        when(compound.attemptExtract(any(), any())).thenReturn(attempt(incomplete, "compound"));
        when(completion.attemptComplete(any(), any(), any(), any()))
            .thenReturn(attempt(response(IntentType.CONFIRMATION_POSITIVE), "completion"))
            .thenReturn(attempt(response(IntentType.INFORMATION), "completion"));
        settings.setCompletionMaxAttempts(2);
        var result = engine().extract(input(false), context, SemanticRecoveryPolicy.strictDefaults());
        assertThat(result.response()).isNull();
        assertThat(result.failure().issueCodes()).contains(IntentExtractionValidator.IssueCode.CONFIRMATION_WITHOUT_PENDING_ACTION);
        verify(completion, times(1)).attemptComplete(any(), any(), any(), any());
        verifyNoInteractions(multi);
    }
}
