package com.ai.infrastructure.intent.orchestration.pipeline.steps;

import com.ai.infrastructure.dto.Intent;
import com.ai.infrastructure.dto.IntentType;
import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.intent.IntentQueryExtractor;
import com.ai.infrastructure.intent.extraction.IntentExtractionInput;
import com.ai.infrastructure.intent.extraction.ProgressiveIntentExtractionEngine;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentExtractionStepProgressiveEngineTest {

    @Test
    void shouldUseProgressiveEngineWhenAvailableAndAttachDiagnostics() {
        IntentQueryExtractor extractor = mock(IntentQueryExtractor.class);
        ProgressiveIntentExtractionEngine engine = mock(ProgressiveIntentExtractionEngine.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<ProgressiveIntentExtractionEngine> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(engine);

        MultiIntentResponse response = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder().type(IntentType.INFORMATION).intent("refund_policy").build()))
            .build();

        ProgressiveIntentExtractionEngine.ExtractionOutput output =
            new ProgressiveIntentExtractionEngine.ExtractionOutput(response, Map.of("extractionPath", "compound"));

        when(engine.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class))).thenReturn(output);

        IntentExtractionStep step = new IntentExtractionStep(extractor, provider);
        PipelineContext ctx = PipelineContext.from("q", OrchestrationContext.forUser("user"));

        PipelineContext updated = step.process(ctx);

        assertThat(updated.getIntentResponse()).isNotNull();
        assertThat(updated.getIntentResponse().hasIntents()).isTrue();
        assertThat(updated.getMetadata()).containsKey("extractionDiagnostics");
        verify(extractor, never()).extract(any(IntentExtractionInput.class), any(OrchestrationContext.class));
    }

    @Test
    void shouldAttachSinglePassDiagnosticsWhenProgressiveEngineUnavailable() {
        IntentQueryExtractor extractor = mock(IntentQueryExtractor.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<ProgressiveIntentExtractionEngine> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        MultiIntentResponse response = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder().type(IntentType.INFORMATION).intent("refund_policy").build()))
            .build();

        when(extractor.extractWithTrace(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(new IntentQueryExtractor.ExtractionTrace(response, 184L, 151L, "gpt-5.4-nano"));

        IntentExtractionStep step = new IntentExtractionStep(extractor, provider);
        PipelineContext ctx = PipelineContext.from("q", OrchestrationContext.forUser("user"));

        PipelineContext updated = step.process(ctx);

        assertThat(updated.getIntentResponse()).isNotNull();
        assertThat(updated.getIntentResponse().hasIntents()).isTrue();
        assertThat(updated.getMetadata())
            .containsEntry("extractionDiagnostics", Map.of(
                "extractionPath", "single_pass",
                "extractionAttempts", 1,
                "llmCalls", 1,
                "processingTimeMs", 184L,
                "providerProcessingTimeMs", 151L,
                "model", "gpt-5.4-nano",
                "attempts", List.of(Map.of(
                    "strategy", "single_pass",
                    "success", true,
                    "llmCalls", 1,
                    "processingTimeMs", 184L,
                    "providerProcessingTimeMs", 151L,
                    "model", "gpt-5.4-nano"
                ))
            ));
        verify(extractor, never()).extract(any(IntentExtractionInput.class), any(OrchestrationContext.class));
    }
}
