package com.subscription.hub.ai;

import ai.fabric.dto.AIChatRole;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResolverChatHistoryEnrichmentStepTest {

    private final ResolverChatHistoryEnrichmentStep step = new ResolverChatHistoryEnrichmentStep();

    @Test
    void enrichesPipelineContextWithBoundedUserAndAssistantHistory() {
        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("92")
            .sessionId("session-1")
            .metadata(Map.of(ResolverChatHistoryEnrichmentStep.METADATA_KEY, List.of(
                Map.of("role", "user", "content", "Why can't I place an order?"),
                Map.of("role", "assistant", "content", "Your account is blocked by a missing payment method."),
                Map.of("role", "system", "content", "This must not be accepted.")
            )))
            .build();
        PipelineContext pipelineContext = PipelineContext.from("ok add it", orchestrationContext);

        PipelineContext enriched = step.process(pipelineContext);

        assertThat(enriched.getHistoryMessages()).hasSize(2);
        assertThat(enriched.getHistoryMessages().get(0).getRole()).isEqualTo(AIChatRole.USER);
        assertThat(enriched.getHistoryMessages().get(0).getContent()).isEqualTo("Why can't I place an order?");
        assertThat(enriched.getHistoryMessages().get(1).getRole()).isEqualTo(AIChatRole.ASSISTANT);
        assertThat(enriched.getHistoryMessages().get(1).getContent())
            .isEqualTo("Your account is blocked by a missing payment method.");
        assertThat(enriched.getMetadata()).containsEntry("resolverChatHistoryTurns", 2);
    }

    @Test
    void leavesContextUnchangedWhenNoHistoryIsPresent() {
        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("92")
            .sessionId("session-1")
            .build();
        PipelineContext pipelineContext = PipelineContext.from("update payment method", orchestrationContext);

        PipelineContext enriched = step.process(pipelineContext);

        assertThat(enriched).isSameAs(pipelineContext);
    }
}
