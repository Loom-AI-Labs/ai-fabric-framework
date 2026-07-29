package ai.fabric.intent.orchestration.pipeline.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.actiondraft.ActionDraftContinuation;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
            new ActionDraftSemanticContinuationStep(analyzer)
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
            new ActionDraftSemanticContinuationStep(analyzer)
                .process(context);

        assertThat(updated).isSameAs(context);
        verify(analyzer, never()).analyze(context);
    }

    private PipelineContext context() {
        return PipelineContext.from(
            "The state is Bristol.",
            OrchestrationContext.builder()
                .userId("user-1")
                .conversationId("conversation-1")
                .build()
        ).toBuilder()
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
