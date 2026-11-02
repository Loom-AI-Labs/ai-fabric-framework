package ai.fabric.intent.orchestration.pipeline.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.actiondraft.ActionDraftContinuation;
import ai.fabric.intent.actiondraft.ActionDraft;
import ai.fabric.intent.actiondraft.ActionDraftStore;
import ai.fabric.intent.actiondraft.InMemoryActionDraftStore;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ActionDraftSemanticContinuationStepTest {

    @Test
    void shouldStoreOnlyAConfirmedSemanticContinuation() {
        ActionDraftContinuationAnalyzer analyzer =
            mock(ActionDraftContinuationAnalyzer.class);
        PipelineContext context = context();
        MultiIntentResponse response = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(IntentType.ACTION)
                .action("update_address")
                .actionParams(Map.of("state", "Bristol"))
                .build()))
            .build();
        when(analyzer.analyze(context)).thenReturn(
            ActionDraftContinuationAnalyzer.AnalysisOutcome.continued(
                response,
                Set.of("state"),
                1,
                "model"
            )
        );

        PipelineContext updated =
            new ActionDraftSemanticContinuationStep(analyzer, mock(ActionDraftStore.class))
                .process(context);

        assertThat(updated.getActionDraftIntentResponse())
            .isSameAs(response);
        assertThat(updated.getMetadata())
            .containsKey("actionDraftSemanticContinuation");
        assertThat(updated.toString()).doesNotContain("16 Dairy Drive");
    }

    @Test
    void shouldNotCallAnalyzerWithoutDraft() {
        ActionDraftContinuationAnalyzer analyzer =
            mock(ActionDraftContinuationAnalyzer.class);
        PipelineContext context = PipelineContext.from(
            "hello",
            OrchestrationContext.forUser("user-1")
        );

        PipelineContext updated =
            new ActionDraftSemanticContinuationStep(analyzer, mock(ActionDraftStore.class))
                .process(context);

        assertThat(updated).isSameAs(context);
        verify(analyzer, never()).analyze(context);
    }

    @Test
    void shouldLeaveAnIndependentTurnForGeneralIntentExtraction() {
        ActionDraftContinuationAnalyzer analyzer =
            mock(ActionDraftContinuationAnalyzer.class);
        PipelineContext context = context();
        when(analyzer.analyze(context)).thenReturn(
            ActionDraftContinuationAnalyzer.AnalysisOutcome.independent(
                1,
                "model"
            )
        );

        ActionDraftStore store = mock(ActionDraftStore.class);
        PipelineContext updated =
            new ActionDraftSemanticContinuationStep(analyzer, store)
                .process(context);

        assertThat(updated.getActionDraftIntentResponse()).isNull();
        assertThat(updated.getMetadata())
            .containsKey("actionDraftSemanticContinuation");
        assertThat(updated.toString()).doesNotContain("16 Dairy Drive");
        verifyNoInteractions(store);
    }

    @Test
    void shouldClearOnlyTheCurrentOwnersDraftAndStopBeforeBusinessExecution() {
        ActionDraftContinuationAnalyzer analyzer = mock(ActionDraftContinuationAnalyzer.class);
        PipelineContext context = context();
        var store = new InMemoryActionDraftStore();
        var draft = new ActionDraft("update_address", Map.of("street", "16 Dairy Drive"), "state", null, null);
        store.saveDraft("conversation-1", "user-1", draft);
        store.saveDraft("conversation-1", "user-2", draft);
        store.saveDraft("conversation-2", "user-1", draft);
        when(analyzer.analyze(context)).thenReturn(ActionDraftContinuationAnalyzer.AnalysisOutcome.cancelled(1, "model"));

        var updated = new ActionDraftSemanticContinuationStep(analyzer, store).process(context);

        assertThat(store.peekDraft("conversation-1", "user-1")).isEmpty();
        assertThat(store.peekDraft("conversation-1", "user-2")).contains(draft);
        assertThat(store.peekDraft("conversation-2", "user-1")).contains(draft);
        assertThat(updated.isShouldTerminate()).isTrue();
        assertThat(updated.getIntentResult().getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(updated.getIntentResult().getData()).containsEntry("actionDraftCancelled", true);
        assertThat(updated.getActionDraftIntentResponse()).isNull();
    }

    @Test
    void shouldNotAcknowledgeCancellationWhenTheStoreCannotClearIt() {
        var analyzer = mock(ActionDraftContinuationAnalyzer.class);
        var store = mock(ActionDraftStore.class);
        var context = context();
        when(analyzer.analyze(context)).thenReturn(ActionDraftContinuationAnalyzer.AnalysisOutcome.cancelled(1, "model"));
        doThrow(new IllegalStateException("storage unavailable")).when(store)
            .popDraft("conversation-1", "user-1", context.getObservedActionDraft());

        assertThatThrownBy(() -> new ActionDraftSemanticContinuationStep(analyzer, store).process(context))
            .isInstanceOf(IllegalStateException.class).hasMessage("storage unavailable");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldNotCancelAReplacementOrACompletedDraftWhileTheModelIsRunning(boolean replace) {
        var analyzer = mock(ActionDraftContinuationAnalyzer.class);
        var store = new InMemoryActionDraftStore();
        var context = context();
        var replacement = new ActionDraft("update_payment_method", Map.of(), "last4", null, null);
        store.saveDraft("conversation-1", "user-1", context.getObservedActionDraft());
        when(analyzer.analyze(context)).thenAnswer(invocation -> {
            if (replace) {
                store.saveDraft("conversation-1", "user-1", replacement);
            } else {
                store.popDraft("conversation-1", "user-1");
            }
            return ActionDraftContinuationAnalyzer.AnalysisOutcome.cancelled(1, "model");
        });

        var updated = new ActionDraftSemanticContinuationStep(analyzer, store).process(context);

        assertThat(updated.getIntentResult().isSuccess()).isFalse();
        assertThat(updated.getIntentResult().getErrorCode()).isEqualTo("ACTION_DRAFT_CHANGED");
        assertThat(updated.getIntentResult().getData()).doesNotContainKey("actionDraftCancelled");
        assertThat(store.peekDraft("conversation-1", "user-1"))
            .isEqualTo(replace ? java.util.Optional.of(replacement) : java.util.Optional.empty());
    }

    @Test
    void shouldNotMutateTheDraftWhenTheModelDecisionFails() {
        var analyzer = mock(ActionDraftContinuationAnalyzer.class);
        var store = mock(ActionDraftStore.class);
        var context = context();
        when(analyzer.analyze(context)).thenReturn(ActionDraftContinuationAnalyzer.AnalysisOutcome.failed("VALIDATION_ERROR", 2));

        var updated = new ActionDraftSemanticContinuationStep(analyzer, store).process(context);

        assertThat(updated.isShouldTerminate()).isFalse();
        verifyNoInteractions(store);
    }

    private PipelineContext context() {
        return PipelineContext.from(
            "The state is Bristol.",
            OrchestrationContext.builder()
                .userId("user-1")
                .conversationId("conversation-1")
                .build()
        ).toBuilder()
            .observedActionDraft(new ActionDraft("update_address", Map.of("street", "16 Dairy Drive"), "state", null, null))
            .actionDraftContinuation(
                new ActionDraftContinuation(
                    "update_address",
                    Map.of("street", "16 Dairy Drive"),
                    List.of("state")
                )
            )
            .build();
    }
}
